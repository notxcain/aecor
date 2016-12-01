import ReleaseTransformations._

lazy val buildSettings = Seq(
  organization := "io.aecor",
  scalaVersion := "2.11.8",
  scalaOrganization := "org.typelevel",
  crossScalaVersions := Seq("2.11.8", "2.12.0")
)

lazy val circeVersion = "0.6.1"
lazy val akkaVersion = "2.4.14"
lazy val akkaHttpVersion = "10.0.0"
lazy val reactiveKafkaVersion = "0.13"
lazy val akkaPersistenceCassandra = "0.21"
lazy val catsVersion = "0.8.1"
lazy val akkaHttpJsonVersion = "1.11.0"
lazy val freekVersion = "0.6.5"
lazy val kryoSerializationVersion = "0.5.1"
lazy val logbackVersion = "1.1.7"

lazy val scalaCheckVersion = "1.13.4"
lazy val scalaTestVersion = "3.0.1"
lazy val scalaCheckShapelessVersion = "1.1.4"
lazy val shapelessVersion = "2.3.2"
lazy val kindProjectorVersion = "0.9.3"
lazy val simulacrumVersion = "0.10.0"
lazy val paradiseVersion = "2.1.0"

lazy val commonSettings = Seq(
    scalacOptions ++= commonScalacOptions,
    resolvers ++= Seq(
      Resolver.bintrayRepo("projectseptemberinc", "maven")
    ),
    libraryDependencies ++= Seq(
      "com.github.mpilquist" %% "simulacrum" % simulacrumVersion,
      compilerPlugin(
        "org.spire-math" %% "kind-projector" % kindProjectorVersion),
      compilerPlugin(
        "org.scalamacros" % "paradise" % paradiseVersion cross CrossVersion.full)
    ),
    parallelExecution in Test := false,
    scalacOptions in (Compile, doc) := (scalacOptions in (Compile, doc)).value
      .filter(_ != "-Xfatal-warnings")
  ) ++ warnUnusedImport

lazy val aecorSettings = buildSettings ++ commonSettings ++ publishSettings

lazy val aecor = project
  .in(file("."))
  .settings(moduleName := "aecor")
  .settings(aecorSettings)
  .settings(noPublishSettings)
  .aggregate(core, api, example, schedule, tests, bench)
  .dependsOn(core,
             api,
             example % "compile-internal",
             tests % "test-internal -> test",
             bench % "compile-internal;test-internal -> test")

lazy val core = project
  .settings(moduleName := "aecor-core")
  .settings(aecorSettings)
  .settings(coreSettings)
  .settings(
    libraryDependencies += "org.scalacheck" %% "scalacheck" % scalaCheckVersion % "test")

lazy val api = project
  .dependsOn(core)
  .settings(moduleName := "aecor-api")
  .settings(aecorSettings)
  .settings(apiSettings)

lazy val schedule = project
  .dependsOn(core)
  .settings(moduleName := "aecor-schedule")
  .settings(aecorSettings)
  .settings(scheduleSettings)

lazy val bench = project
  .dependsOn(core, example)
  .settings(moduleName := "aecor-bench")
  .settings(aecorSettings)
  .settings(noPublishSettings)
  .enablePlugins(JmhPlugin)

lazy val tests = project
  .dependsOn(core, example, schedule)
  .settings(moduleName := "aecor-tests")
  .settings(aecorSettings)
  .settings(noPublishSettings)
  .settings(testingSettings)

lazy val example = project
  .dependsOn(core, api, schedule)
  .settings(moduleName := "aecor-example")
  .settings(aecorSettings)
  .settings(noPublishSettings)
  .settings(exampleSettings)

lazy val coreSettings = Seq(
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-query-experimental" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-cassandra" % akkaPersistenceCassandra,
    "com.typesafe.akka" %% "akka-stream-kafka" % reactiveKafkaVersion,
    "ch.qos.logback" % "logback-classic" % logbackVersion,
    "com.chuusai" %% "shapeless" % shapelessVersion,
    "org.typelevel" %% "cats" % catsVersion
  )
)

lazy val apiSettings = Seq(
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion
  )
)

lazy val scheduleSettings = commonProtobufSettings

lazy val exampleSettings = Seq(
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
    "de.heikoseeberger" %% "akka-http-circe" % akkaHttpJsonVersion,
    "com.projectseptember" %% "freek" % freekVersion,
    "io.circe" %% "circe-core" % circeVersion,
    "io.circe" %% "circe-generic" % circeVersion,
    "io.circe" %% "circe-parser" % circeVersion
  )
)

lazy val testingSettings = Seq(
  libraryDependencies ++= Seq(
    "org.scalacheck" %% "scalacheck" % scalaCheckVersion % Test,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
    "com.github.alexarchambault" %% "scalacheck-shapeless_1.13" % scalaCheckShapelessVersion % Test
  )
)

lazy val commonProtobufSettings =
  Seq(
    PB.targets in Compile := Seq(
      scalapb.gen(flatPackage = true) -> (sourceManaged in Compile).value
    )
  )

lazy val commonScalacOptions = Seq(
  "-deprecation",
  "-encoding",
  "UTF-8",
  "-feature",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:experimental.macros",
  "-unchecked",
  "-Xfatal-warnings",
  "-Xlint",
  "-Yno-adapted-args",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
  "-Ywarn-value-discard",
  "-Ywarn-unused-import",
  "-Ypartial-unification"
)

lazy val warnUnusedImport = Seq(
  scalacOptions in (Compile, console) ~= {
    _.filterNot(Set("-Ywarn-unused-import", "-Ywarn-value-discard"))
  },
  scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value
)

lazy val noPublishSettings = Seq(
  publish := (),
  publishLocal := (),
  publishArtifact := false
)

lazy val publishSettings = Seq(
  releaseCommitMessage := s"Set version to ${if (releaseUseGlobalVersion.value) (version in ThisBuild).value
  else version.value}",
  releaseIgnoreUntrackedFiles := true,
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,
  homepage := Some(url("https://github.com/notxcain/aecor")),
  licenses := Seq("MIT" -> url("http://opensource.org/licenses/MIT")),
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := { _ =>
    false
  },
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },
  autoAPIMappings := true,
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/notxcain/aecor"),
      "scm:git:git@github.com:notxcain/aecor.git"
    )
  ),
  pomExtra :=
    <developers>
      <developer>
        <id>notxcain</id>
        <name>Denis Mikhaylov</name>
        <url>https://github.com/notxcain</url>
      </developer>
    </developers>
)

lazy val sharedReleaseProcess = Seq(
  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    publishArtifacts,
    setNextVersion,
    commitNextVersion,
    ReleaseStep(action = Command.process("sonatypeReleaseAll", _)),
    pushChanges
  )
)

addCommandAlias("validate", ";compile;test")
