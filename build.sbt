ThisBuild / scalaVersion := "3.7.1"
ThisBuild / organization := "io.github.triage4s"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val common = Seq(
  scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
  libraryDependencies ++= Seq(
    "com.lihaoyi" %% "upickle" % "4.4.3",
    "org.scalatest" %% "scalatest" % "3.2.20" % Test
  )
)

lazy val core = project.in(file("modules/core")).settings(common).settings(
  name := "triage4s-core",
  libraryDependencies += "org.llm4s" %% "llm4s-core" % "0.4.1"
)

lazy val demo = project.in(file("modules/demo")).dependsOn(core).settings(common).settings(
  name := "triage4s-demo",
  publish / skip := true,
  libraryDependencies += "com.lihaoyi" %% "cask" % "0.10.2",
  Compile / run / fork := true,
  Compile / run / baseDirectory := (ThisBuild / baseDirectory).value,
  Compile / mainClass := Some("triage4s.demo.Main")
)

lazy val root = project.in(file(".")).aggregate(core, demo).settings(
  name := "triage4s",
  publish / skip := true
)
