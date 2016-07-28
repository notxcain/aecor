logLevel := Level.Warn

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.1.0-RC1")
addSbtPlugin("com.trueaccord.scalapb" % "sbt-scalapb" % "0.5.26")
addSbtPlugin("com.eed3si9n"         % "sbt-unidoc"            % "0.3.2")
addSbtPlugin("com.github.gseitz"    % "sbt-release"           % "1.0.0")
addSbtPlugin("pl.project13.scala"   % "sbt-jmh"               % "0.2.6")
addSbtPlugin("org.scalastyle"      %% "scalastyle-sbt-plugin" % "0.8.0")
addSbtPlugin("com.typesafe.sbt"     % "sbt-git"               % "0.8.4")

libraryDependencies += "com.github.os72" % "protoc-jar" % "3.0.0-b2"