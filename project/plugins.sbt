// Plugin versions are mirrored, for review only, in the commented
// "Plugin and launcher ledger" block of project/Dependencies.scala.
// sbt loads this file before it compiles project/*.scala, so these
// literals cannot be read from that ledger. Keep both in step.
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.5.0")
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.13.0")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")
