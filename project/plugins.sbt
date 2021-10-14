logLevel := Level.Warn

libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.9.6"

addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.12")
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "1.0.0")
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "2.0.2")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "2.6")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.3.7")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.25")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.3")
addSbtPlugin("com.thesamet" % "sbt-protoc" % "0.99.27")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.3.0")
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.4.3")
addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.1.10")
