logLevel := Level.Warn

addSbtPlugin("com.trueaccord.scalapb" % "sbt-scalapb" % "0.5.26")

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.2.0-M5")
addSbtPlugin("com.eed3si9n"         % "sbt-unidoc"            % "0.3.2")
addSbtPlugin("com.github.gseitz"    % "sbt-release"           % "1.0.3")
addSbtPlugin("pl.project13.scala"   % "sbt-jmh"               % "0.2.6")
addSbtPlugin("org.scalastyle"      %% "scalastyle-sbt-plugin" % "0.8.0")
addSbtPlugin("com.typesafe.sbt"     % "sbt-git"               % "0.8.5")
addSbtPlugin("com.jsuereth"         % "sbt-pgp"               % "1.0.0")
addSbtPlugin("org.xerial.sbt"       % "sbt-sonatype"          %  "1.1")

libraryDependencies += "com.github.os72" % "protoc-jar" % "3.0.0-b2"