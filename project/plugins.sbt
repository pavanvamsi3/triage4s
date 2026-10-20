addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.5")
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.1")
// tag-driven releases to Maven Central: version from git (sbt-dynver), signing and publishing (#96)
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.12.1")
