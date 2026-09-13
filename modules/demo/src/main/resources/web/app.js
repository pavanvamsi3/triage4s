"use strict";

(() => {
  const $ = (id) => document.getElementById(id);
  const state = {
    config: null, mode: "live", loaded: false, issues: [], selected: new Set(),
    activeId: null, results: new Map(), run: null, busy: false, pollTimer: null,
    checkingRun: true,
  };
  const modeCopy = {
    live: {
      load: "Load issues",
      description: "Fetches public issues from GitHub only when you load. Pull requests are excluded. Loading does not call OpenAI.",
    },
    snapshot: {
      load: "Load snapshot",
      description: "Loads saved inputs from local disk, not GitHub. Running triage still sends selected titles and bodies to OpenAI.",
    },
    replay: {
      load: "Load recording",
      description: "Loads genuine recorded inputs and results from local disk. Replay makes no GitHub or model calls; results are not newly generated.",
    },
  };
  const statusLabels = {
    queued: "Queued", running: "In progress", suggested: "Suggested",
    "needs-review": "Needs review", error: "Error",
  };
  // uPickle encodes Scala Option[A] as [] or [A], not null or A.
  const option = (value) => Array.isArray(value) ? (value.length ? value[0] : null) : (value ?? null);
  const issueId = (issue) => String(issue.input.id);
  const limit = () => Math.min(10, Math.max(1, Number(state.config?.limits?.maxIssues) || 10));
  const running = () => state.run?.status === "running";
  const locked = () => state.busy || running() || state.checkingRun;
  const canRun = () => state.loaded && !locked() && state.selected.size > 0 &&
    (state.mode === "replay" || (state.config?.configured && $("consent").checked));

  function node(tag, className, text) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined && text !== null) element.textContent = String(text);
    return element;
  }

  function showError(error) {
    $("error-text").textContent = error instanceof Error ? error.message : String(error);
    $("error-banner").hidden = false;
    $("success-banner").hidden = true;
  }

  function success(message) {
    $("success-banner").textContent = message;
    $("success-banner").hidden = false;
  }

  async function api(path, body) {
    const response = await fetch(path, {
      method: body === undefined ? "GET" : "POST",
      headers: body === undefined ? { Accept: "application/json" } : { Accept: "application/json", "Content-Type": "application/json" },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      cache: "no-store",
    });
    let data;
    try {
      data = await response.json();
    } catch {
      throw new Error(`The local server returned an unreadable response (HTTP ${response.status}).`);
    }
    if (!response.ok) throw new Error(data.error || `Request failed (HTTP ${response.status}).`);
    return data;
  }

  function badge(status) {
    return node("span", `badge ${status}`, statusLabels[status] || status);
  }

  function updateControls() {
    const isLocked = locked();
    const replay = state.mode === "replay";
    document.querySelectorAll('input[name="mode"]').forEach((input) => { input.disabled = isLocked; });
    $("issue-state").disabled = isLocked;
    $("issue-pages").disabled = isLocked;
    $("live-filters").hidden = state.mode !== "live";
    $("load-issues").disabled = isLocked;
    $("load-issues").textContent = state.busy ? "Working…" : modeCopy[state.mode].load;
    $("mode-description").textContent = modeCopy[state.mode].description;
    $("save-snapshot").hidden = replay;
    $("save-snapshot").disabled = isLocked || !state.loaded || !state.issues.length;
    $("selection-count").textContent = `${state.selected.size} / ${limit()} selected`;
    $("select-all").disabled = isLocked || !state.issues.length || !state.loaded;
    $("select-all").checked = state.selected.size > 0 && state.selected.size === Math.min(limit(), state.issues.length);
    $("select-all").indeterminate = state.selected.size > 0 && !$("select-all").checked;
    $("select-all").title = `Select up to ${limit()} issues`;
    $("clear-selection").disabled = isLocked || !state.selected.size;
    $("consent-label").hidden = replay;
    $("consent").disabled = isLocked || !state.loaded;
    $("run-triage").textContent = running() ? "Processing…" : replay ? "Replay selected results" : "Run triage →";
    $("run-triage").disabled = !canRun();
    $("run-heading").textContent = replay ? "Revisit a recorded perspective." : "Ready for a second perspective?";
    $("run-disclosure").textContent = replay
      ? "Recorded replay only. The backend reads saved results without GitHub or OpenAI calls. Capture metadata identifies the original recording."
      : "Only the selected issue titles and bodies, alongside taxonomy rules, are sent to OpenAI. Existing labels are not classification input.";
    $("run-hint").textContent = state.checkingRun ? "Checking for an existing run…"
      : running() ? "One issue at a time. Please keep this page open."
      : !state.loaded ? `Load and select up to ${limit()} issues.`
      : !replay && !state.config?.configured ? "OpenAI is not configured. Replay is still available."
      : !state.selected.size ? `Select up to ${limit()} issues to continue.`
      : !replay && !$("consent").checked ? "Review the content and confirm consent first."
      : `${state.selected.size} selected · ${replay ? "no live model calls" : "manual action required"}`;
    const recordable = state.run?.status === "completed" && state.run.mode !== "replay" &&
      state.run.items.some((item) => item.status === "suggested" || item.status === "needs-review");
    $("record-run").hidden = !recordable;
    $("record-run").disabled = isLocked;
    document.querySelectorAll(".issue-select").forEach((input) => {
      input.disabled = isLocked || !state.loaded || (!input.checked && state.selected.size >= limit());
    });
    document.querySelectorAll(".retry-button").forEach((button) => {
      button.disabled = !state.loaded || isLocked || (!replay && !state.config?.configured);
    });
  }

  function empty(container, title, description) {
    const wrapper = node("div", "empty-state");
    wrapper.append(node("span", "empty-icon", "≡"), node("h3", "", title), node("p", "", description));
    container.replaceChildren(wrapper);
  }

  function renderList() {
    const focused = $("issue-list").contains(document.activeElement) ? document.activeElement : null;
    const focusedId = focused?.dataset.issueId;
    const focusedClass = focused?.className;
    $("issue-count").textContent = `${state.issues.length} issue${state.issues.length === 1 ? "" : "s"}`;
    if (!state.issues.length) {
      empty($("issue-list"), state.loaded ? "No issues in this source" : "Your issue queue starts here",
        state.loaded ? "Try a different state or source, then load again." : "Choose a source and load issues. Nothing is fetched automatically.");
      updateControls();
      return;
    }
    const fragment = document.createDocumentFragment();
    for (const issue of state.issues) {
      const id = issueId(issue);
      const row = node("div", `issue-row${state.activeId === id ? " active" : ""}`);
      const checkbox = node("input", "issue-select");
      checkbox.dataset.issueId = id;
      checkbox.type = "checkbox";
      checkbox.checked = state.selected.has(id);
      checkbox.setAttribute("aria-label", `Select issue ${id}: ${issue.input.title}`);
      checkbox.addEventListener("change", () => {
        if (checkbox.checked && state.selected.size < limit()) state.selected.add(id);
        else state.selected.delete(id);
        $("consent").checked = false;
        renderList();
      });
      const open = node("button", "issue-open");
      open.dataset.issueId = id;
      open.type = "button";
      open.setAttribute("aria-pressed", String(state.activeId === id));
      open.append(node("span", "issue-title", issue.input.title));
      const meta = node("span", "issue-meta");
      meta.append(node("span", "", `#${id}`), node("span", "", issue.state));
      const item = state.results.get(id);
      if (item) meta.append(badge(item.status));
      else meta.append(node("span", "", "Not triaged"));
      open.append(meta);
      open.addEventListener("click", () => {
        state.activeId = id;
        document.querySelectorAll(".issue-row").forEach((entry) => entry.classList.remove("active"));
        document.querySelectorAll(".issue-open").forEach((entry) => entry.setAttribute("aria-pressed", "false"));
        row.classList.add("active");
        open.setAttribute("aria-pressed", "true");
        renderDetail();
      });
      row.append(checkbox, open);
      fragment.append(row);
    }
    $("issue-list").replaceChildren(fragment);
    updateControls();
    if (focusedId) {
      const replacement = [...$("issue-list").querySelectorAll("button, input")]
        .find((element) => element.dataset.issueId === focusedId && element.className === focusedClass);
      replacement?.focus({ preventScroll: true });
    }
  }

  function highlighted(text, evidence, field) {
    const fragment = document.createDocumentFragment();
    const source = String(text || "");
    const ranges = [];
    for (const entry of evidence.filter((item) => item.field === field && typeof item.quote === "string" && item.quote.length)) {
      const start = source.indexOf(entry.quote);
      if (start >= 0) ranges.push([start, start + entry.quote.length]);
    }
    ranges.sort((a, b) => a[0] - b[0]);
    const merged = [];
    for (const range of ranges) {
      const last = merged[merged.length - 1];
      if (last && range[0] <= last[1]) last[1] = Math.max(last[1], range[1]);
      else merged.push([...range]);
    }
    let cursor = 0;
    for (const [start, end] of merged) {
      fragment.append(document.createTextNode(source.slice(cursor, start)), node("mark", "", source.slice(start, end)));
      cursor = end;
    }
    fragment.append(document.createTextNode(source.slice(cursor)));
    return fragment;
  }

  function renderDetail() {
    const issue = state.issues.find((entry) => issueId(entry) === state.activeId);
    if (!issue) {
      empty($("issue-detail"), "Context before classification", "Open an issue to inspect its source. Suggestions and exact source evidence appear here after a run.");
      return;
    }
    const item = state.results.get(issueId(issue));
    const response = option(item?.response);
    const result = response?.result;
    const evidence = result?.evidence || [];
    const content = node("div", "detail-content");
    const topline = node("div", "detail-topline");
    topline.append(node("span", "", `ISSUE #${issueId(issue)}`), node("span", "badge", issue.state));
    if (state.mode === "replay") topline.append(node("span", "badge replay", "Recorded replay"));
    if (typeof issue.url === "string" && /^https:\/\/github\.com\/pavanvamsi3\/copilot-lens\/issues\/[0-9]+$/.test(issue.url)) {
      const link = node("a", "", "View on GitHub ↗");
      link.href = issue.url;
      link.target = "_blank";
      link.rel = "noopener noreferrer";
      topline.append(link);
    }
    const title = node("h3", "detail-title");
    title.append(highlighted(issue.input.title, evidence, "title"));
    const labels = node("div", "existing-labels");
    labels.append(node("span", "", "Existing GitHub labels · not AI predictions:"));
    if (issue.labels?.length) issue.labels.forEach((label) => labels.append(node("span", "badge", label)));
    else labels.append(node("span", "", "None"));
    content.append(topline, title, labels);

    if (result) {
      const prediction = node("section", "prediction");
      const heading = node("div", "prediction-heading");
      heading.append(node("h3", "", state.mode === "replay" ? "Recorded AI suggestion" : "AI suggestion"), badge(item.status));
      const values = node("dl", "predictions");
      for (const [label, value] of [["Issue type", result.issueType], ["Component", result.component]]) {
        const group = node("div");
        group.append(node("dt", "", label), node("dd", "", option(value) ?? "Abstained"));
        values.append(group);
      }
      prediction.append(heading, values, node("p", "rationale", result.rationale));
      if (result.needsReview || result.reviewReasons?.length) {
        const review = node("div", "review-reasons");
        review.append(node("strong", "", "Human review needed"));
        const reasons = node("ul");
        (result.reviewReasons || []).forEach((reason) => reasons.append(node("li", "", reason)));
        review.append(reasons);
        prediction.append(review);
      }
      const usage = option(response.usage);
      const usageText = usage ? `${usage.prompt} input · ${usage.completion} output · ${usage.total} total tokens` : "Token usage unavailable";
      prediction.append(node("p", "usage", `${response.model || "Model unavailable"} · ${usageText}`));
      content.append(prediction);
      const evidenceSection = node("section", "source-section");
      evidenceSection.append(node("h3", "", `Source-linked evidence · ${evidence.length} excerpt${evidence.length === 1 ? "" : "s"}`));
      if (!evidence.length) evidenceSection.append(node("p", "fine-print", "No classification evidence returned. Review the abstention reasons."));
      for (const entry of evidence) {
        const block = node("div", "evidence-item");
        block.append(node("small", "", `${entry.target === "issueType" ? "Issue type" : "Component"} · ${entry.field}`));
        const quote = node("blockquote");
        quote.append(node("mark", "", entry.quote));
        block.append(quote);
        evidenceSection.append(block);
      }
      content.append(evidenceSection);
    } else if (item?.status === "error") {
      const error = node("div", "item-error");
      error.append(node("strong", "", "This issue could not be classified."), node("p", "", option(item.error) || "An unknown error occurred."));
      const retry = node("button", "button secondary retry-button", state.mode === "replay" ? "Retry recorded result" : "Retry this issue");
      retry.type = "button";
      retry.addEventListener("click", () => {
        const id = issueId(issue);
        if (state.mode !== "replay" && (!state.selected.has(id) || !$("consent").checked)) {
          state.selected.clear();
          state.selected.add(id);
          $("consent").checked = false;
          renderList();
          success("This issue is selected for retry. Review its content, confirm consent below, then choose Run triage.");
          $("consent").focus();
          return;
        }
        startRun([id]);
      });
      error.append(retry, node("p", "fine-print", "Manual retry starts a new run. Wait for the current run to finish; live and snapshot retries require consent."));
      content.append(error);
    } else {
      content.append(node("p", "awaiting", item ? `${statusLabels[item.status] || item.status} · Suggestions will appear here when processing finishes.` : "No suggestion yet. Review this source, select the issue, then explicitly start a run."));
    }
    const source = node("section", "source-section");
    source.append(node("h3", "", "Original issue body"));
    const body = node("pre", "source-body");
    body.append(highlighted(issue.input.body || "(No body provided)", evidence, "body"));
    source.append(body);
    content.append(source);
    const metadata = node("details", "metadata");
    metadata.append(node("summary", "", "Source metadata"), node("p", "", `Retrieved: ${issue.retrievedAt || "Unavailable"}`), node("p", "", `Content hash: ${issue.hash || "Unavailable"}`));
    content.append(metadata);
    const scroll = $("issue-detail").scrollTop;
    $("issue-detail").replaceChildren(content);
    $("issue-detail").scrollTop = scroll;
    updateControls();
  }

  function renderRun() {
    const run = state.run;
    $("progress-region").hidden = !run || run.status === "idle";
    if (!run || run.status === "idle") return;
    const finished = run.items.filter((item) => ["suggested", "needs-review", "error"].includes(item.status)).length;
    const errors = run.items.filter((item) => item.status === "error").length;
    $("run-status").textContent = `${run.mode === "replay" ? "Recorded replay" : "Triage"} ${run.status === "running" ? "in progress" : "complete"}${errors ? ` · ${errors} error${errors === 1 ? "" : "s"}` : ""}`;
    $("progress-text").textContent = `${finished} / ${run.items.length} processed`;
    $("run-progress").max = Math.max(1, run.items.length);
    $("run-progress").value = finished;
    $("run-notice").textContent = run.notice || "";
  }

  function acceptRun(run, hydrate = false) {
    state.run = run;
    if (run.status !== "idle") {
      if (hydrate && !state.loaded) {
        state.mode = run.mode;
        document.querySelectorAll('input[name="mode"]').forEach((input) => { input.checked = input.value === state.mode; });
        state.issues = run.items.map((item) => item.issue);
        state.activeId = state.issues.length ? issueId(state.issues[0]) : null;
        $("source-notice").textContent = "Showing the server's current run. Load a source explicitly before starting another run.";
        $("source-notice").hidden = false;
      }
      run.items.forEach((item) => state.results.set(issueId(item.issue), item));
    }
    renderRun();
    renderList();
    renderDetail();
  }

  async function pollRun() {
    clearTimeout(state.pollTimer);
    try {
      const run = await api("/api/run");
      state.checkingRun = false;
      $("resume-polling").hidden = true;
      acceptRun(run, true);
      if (run.status === "running") state.pollTimer = setTimeout(pollRun, 1100);
    } catch (error) {
      showError(`Unable to check run status: ${error.message} Use “Refresh run status” before starting more work.`);
      state.checkingRun = true;
      $("progress-region").hidden = false;
      $("resume-polling").hidden = false;
      updateControls();
    }
  }

  async function loadIssues() {
    if (locked()) return;
    state.busy = true;
    $("error-banner").hidden = true;
    $("success-banner").hidden = true;
    $("consent").checked = false;
    updateControls();
    try {
      const query = new URLSearchParams({ mode: state.mode, state: $("issue-state").value, pages: $("issue-pages").value });
      const data = await api(`/api/issues?${query}`);
      state.loaded = true;
      state.issues = data.issues;
      state.results.clear();
      state.selected.clear();
      state.run = null;
      state.activeId = data.issues.length ? issueId(data.issues[0]) : null;
      $("source-notice").textContent = data.notice || `${data.issues.length} issues loaded.`;
      $("source-notice").hidden = false;
      $("replay-capture").hidden = state.mode !== "replay";
      $("replay-capture").textContent = `RECORDED REPLAY · Original capture: ${option(data.replayCapturedAt) || "Capture metadata unavailable"}. No live model call.`;
      renderRun();
      renderList();
      renderDetail();
    } catch (error) {
      showError(error);
    } finally {
      state.busy = false;
      updateControls();
    }
  }

  async function startRun(ids) {
    if (locked() || !state.loaded || !ids.length || ids.length > limit()) return;
    const consent = state.mode === "replay" || $("consent").checked;
    if (!consent || (state.mode !== "replay" && !state.config?.configured)) {
      showError("Review the public content and confirm consent before sending it to the configured OpenAI model.");
      return;
    }
    state.busy = true;
    $("error-banner").hidden = true;
    $("success-banner").hidden = true;
    updateControls();
    try {
      const run = await api("/api/run", { ids, consent });
      acceptRun(run);
      if (run.status === "running") state.pollTimer = setTimeout(pollRun, 1100);
    } catch (error) {
      showError(error);
      // A dropped response may still have started work; reconcile before unlocking.
      state.checkingRun = true;
      await pollRun();
    } finally {
      state.busy = false;
      updateControls();
    }
  }

  async function save(path) {
    if (locked()) return;
    state.busy = true;
    $("error-banner").hidden = true;
    updateControls();
    try {
      const data = await api(path, {});
      success(data.message);
    } catch (error) {
      showError(error);
    } finally {
      state.busy = false;
      updateControls();
    }
  }

  document.querySelectorAll('input[name="mode"]').forEach((input) => input.addEventListener("change", () => {
    if (locked()) return;
    state.mode = input.value;
    state.loaded = false;
    state.issues = [];
    state.selected.clear();
    state.results.clear();
    state.activeId = null;
    state.run = null;
    $("consent").checked = false;
    $("source-notice").hidden = true;
    $("replay-capture").hidden = true;
    $("success-banner").hidden = true;
    renderList();
    renderDetail();
    renderRun();
  }));
  $("load-issues").addEventListener("click", loadIssues);
  $("save-snapshot").addEventListener("click", () => save("/api/snapshot"));
  $("record-run").addEventListener("click", () => save("/api/record"));
  $("run-triage").addEventListener("click", () => startRun([...state.selected]));
  $("consent").addEventListener("change", updateControls);
  $("dismiss-error").addEventListener("click", () => { $("error-banner").hidden = true; });
  $("resume-polling").addEventListener("click", pollRun);
  $("select-all").addEventListener("change", (event) => {
    state.selected.clear();
    if (event.target.checked) state.issues.slice(0, limit()).forEach((issue) => state.selected.add(issueId(issue)));
    $("consent").checked = false;
    renderList();
  });
  $("clear-selection").addEventListener("click", () => {
    state.selected.clear();
    $("consent").checked = false;
    renderList();
  });

  async function initialize() {
    updateControls();
    try {
      const config = await api("/api/config");
      state.config = config;
      $("repository-name").textContent = config.repository;
      $("provider-status").textContent = config.configured ? "OpenAI configured" : "OpenAI not configured";
      $("provider-dot").classList.toggle("ready", config.configured);
      $("model-name").textContent = config.model || "Set the model at the server edge";
      $("transport-notice").textContent = config.transportNotice || "";
      $("taxonomy").textContent = JSON.stringify(config.taxonomy, null, 2);
    } catch (error) {
      $("provider-status").textContent = "Configuration unavailable";
      $("taxonomy").textContent = "Configuration could not be loaded. Reload the page to try again.";
      showError(error);
    }
    await pollRun();
    updateControls();
  }

  initialize();
})();
