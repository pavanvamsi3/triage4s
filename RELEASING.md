# Releasing

`triage4s-core` is published to Maven Central as `io.github.pavanvamsi3 %% triage4s-core` by
[sbt-ci-release](https://github.com/sbt/sbt-ci-release). The demo and the CLI are not published.

## One-time setup (a maintainer with access to the GitHub account `pavanvamsi3`)

1. Sign in to [central.sonatype.com](https://central.sonatype.com) with GitHub and verify the namespace
   `io.github.pavanvamsi3`. Sonatype verifies an `io.github.<account>` namespace by asking its owner to create a
   temporary public repository with a given name.
2. Generate a user token there (Account, Generate User Token).
3. Create a GPG key for signing, and publish its public half to a key server:

   ```bash
   gpg --gen-key
   gpg --keyserver keyserver.ubuntu.com --send-keys <LONG_KEY_ID>
   gpg --armor --export-secret-keys <LONG_KEY_ID> | base64 | pbcopy   # the PGP_SECRET value (base64 -w0 on Linux)
   ```

4. Add these repository secrets (Settings, Secrets and variables, Actions):

   | Secret | Value |
   | --- | --- |
   | `SONATYPE_USERNAME` | the user token's username |
   | `SONATYPE_PASSWORD` | the user token's password |
   | `PGP_SECRET` | the base64-encoded secret key from step 3 |
   | `PGP_PASSPHRASE` | its passphrase |

## Each release

1. Make sure `main` is green.
2. Tag the commit and push the tag; the version is the tag without its `v`:

   ```bash
   git tag -a v0.1.0 -m "triage4s 0.1.0"
   git push origin v0.1.0
   ```

3. The **Release** workflow tests the build, signs `triage4s-core` and publishes it. It appears on Maven Central within
   about 30 minutes, and on [Scaladex](https://index.scala-lang.org) later.
4. Write the release notes on GitHub (Releases, Draft a new release from the tag).

Versions follow [early semver](https://www.scala-lang.org/blog/2021/02/16/preventing-version-conflicts-with-versionscheme.html):
before 1.0, a minor release (0.2.0) may break the API and a patch release (0.1.1) does not. Commits that are not tagged
build as `-SNAPSHOT` versions of the next release.
