ThisBuild / scalaVersion := "3.7.1"
ThisBuild / organization := "io.github.triage4s"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / homepage := Some(url("https://github.com/pavanvamsi3/triage4s"))

// One LLM4S version for every LLM4S module: since 0.5.0 the library is split into modules that are released together.
// `-Dllm4s.version=...` builds against another one, such as the snapshot of LLM4S main the nightly workflow publishes.
val llm4sVersion = sys.props.getOrElse("llm4s.version", "0.6.0")

lazy val common = Seq(
  scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
  libraryDependencies ++= Seq(
    "com.lihaoyi" %% "upickle" % "4.4.3",
    "org.scalatest" %% "scalatest" % "3.2.20" % Test
  )
)

lazy val core = project.in(file("modules/core")).settings(common).settings(
  name := "triage4s-core",
  // the core library needs only LLMClient and the model types; it ships no provider client
  libraryDependencies ++= Seq(
    "org.llm4s" %% "llm4s-core" % llm4sVersion,
    // ScriptedLLMClient: a scripted model that records every request, for the tests
    "org.llm4s" %% "llm4s-testkit" % llm4sVersion % Test
  )
)

lazy val demo = project.in(file("modules/demo")).dependsOn(core).enablePlugins(JavaAppPackaging).settings(common).settings(
  // `sbt demo/stage` lays the app out under target/universal/stage, with a bin/triage4s launcher (#101)
  executableScriptName := "triage4s",
  name := "triage4s-demo",
  publish / skip := true,
  libraryDependencies ++= Seq(
    "com.lihaoyi" %% "cask" % "0.10.2",
    // OpenAIClient (and, through llm4s-openai-compatible, OpenAIConfig) live in their own module since LLM4S 0.5.0
    "org.llm4s" %% "llm4s-openai" % llm4sVersion,
    // the other providers TRIAGE_PROVIDER can select; each is discovered from the classpath (#88)
    "org.llm4s" %% "llm4s-anthropic" % llm4sVersion,
    "org.llm4s" %% "llm4s-gemini" % llm4sVersion,
    "org.llm4s" %% "llm4s-ollama" % llm4sVersion,
    // ScriptedLLMClient, for the reliability tests
    "org.llm4s" %% "llm4s-testkit" % llm4sVersion % Test
  ),
  Compile / run / fork := true,
  Compile / run / baseDirectory := (ThisBuild / baseDirectory).value,
  Compile / mainClass := Some("triage4s.demo.Main")
)

// `triage4s classify`: one issue from a file or GitHub, in the terminal or a script (#99)
lazy val cli = project.in(file("modules/cli")).dependsOn(core).settings(common).settings(
  name := "triage4s-cli",
  publish / skip := true,
  libraryDependencies ++= Seq(
    "org.llm4s" %% "llm4s-openai" % llm4sVersion,
    "org.llm4s" %% "llm4s-anthropic" % llm4sVersion,
    "org.llm4s" %% "llm4s-gemini" % llm4sVersion,
    "org.llm4s" %% "llm4s-ollama" % llm4sVersion,
    "ch.qos.logback" % "logback-classic" % "1.5.34",
    "org.llm4s" %% "llm4s-testkit" % llm4sVersion % Test
  ),
  Compile / run / fork := true,
  Compile / mainClass := Some("triage4s.cli.Main")
)

lazy val root = project.in(file(".")).aggregate(core, demo, cli).settings(
  name := "triage4s",
  publish / skip := true
)
