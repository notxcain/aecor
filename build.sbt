import ReleaseTransformations._
import sbtrelease.Version.Bump
import pl.project13.scala.sbt._

lazy val buildSettings = inThisBuild(
  Seq(
    organization := "io.aecor",
    scalaVersion := "2.11.12",
    crossScalaVersions := Seq("2.11.12", "2.12.4")
  )
)

lazy val akkaVersion = "2.5.9"
lazy val akkaPersistenceCassandraVersion = "0.59"
lazy val akkaPersistenceJdbcVersion = "3.2.0"
lazy val catsVersion = "1.0.1"
lazy val catsEffectVersion = "0.8"
lazy val logbackVersion = "1.1.7"
lazy val cassandraDriverExtrasVersion = "3.1.0"
lazy val jsr305Version = "3.0.1"
lazy val boopickleVersion = "1.2.6"

lazy val monixVersion = "3.0.0-M3"
lazy val scalaCheckVersion = "1.13.4"
lazy val scalaTestVersion = "3.0.1"
lazy val scalaCheckShapelessVersion = "1.1.4"
lazy val shapelessVersion = "2.3.3"
lazy val kindProjectorVersion = "0.9.4"
lazy val simulacrumVersion = "0.11.0"
lazy val scalametaVersion = "1.8.0"

// Example dependencies

lazy val circeVersion = "0.9.0"
lazy val akkaHttpVersion = "10.0.11"
lazy val akkaHttpJsonVersion = "1.19.0"
lazy val scalametaParadiseVersion = "3.0.0-M10"

lazy val liberatorVersion = "0.7.0"

lazy val commonSettings = Seq(
  scalacOptions ++= commonScalacOptions,
  addCompilerPlugin("org.spire-math" %% "kind-projector" % kindProjectorVersion),
  addCompilerPlugin("org.scalameta" % "paradise" % scalametaParadiseVersion cross CrossVersion.patch),
  parallelExecution in Test := false,
  scalacOptions in (Compile, doc) := (scalacOptions in (Compile, doc)).value
    .filter(_ != "-Xfatal-warnings")
) ++ warnUnusedImport

lazy val aecorSettings = buildSettings ++ commonSettings ++ publishSettings

lazy val aecor = project
  .in(file("."))
  .withId("aecor")
  .settings(moduleName := "aecor", name := "Aecor")
  .settings(aecorSettings)
  .settings(noPublishSettings)
  .aggregate(
    core,
    boopickleWireProtocol,
    akkaPersistence,
    akkaGeneric,
    distributedProcessing,
    example,
    schedule,
    testKit,
    tests,
    benchmarks
  )

def aecorModule(id: String, description: String): Project =
  Project(id, file(s"modules/$id"))
   .settings(
     moduleName := id,
     name := description
   )

lazy val core = aecorModule("core", "Aecor Core")
    .settings(aecorSettings)
    .settings(coreSettings)

lazy val boopickleWireProtocol = aecorModule("boopickle-wire-protocol", "Aecor Boopickle Wire Protocol derivation")
    .dependsOn(core)
   .settings(aecorSettings)
   .settings(boopickleWireProtocolSettings)

lazy val akkaPersistence = aecorModule("akka-persistence-runtime",
    "Aecor Runtime based on Akka Cluster Sharding and Persistence"
  )
  .dependsOn(core)
  .settings(aecorSettings)
  .settings(akkaPersistenceSettings)

lazy val akkaGeneric = aecorModule("akka-cluster-runtime", "Aecor Runtime based on Akka Cluster Sharding")
  .dependsOn(core)
  .settings(aecorSettings)
  .settings(akkaPersistenceSettings)

lazy val distributedProcessing =
  aecorModule("distributed-processing", "Aecor Distributed Processing")
    .dependsOn(core)
    .settings(aecorSettings)
    .settings(distributedProcessingSettings)

lazy val schedule = aecorModule("schedule", "Aecor Schedule")
  .dependsOn(akkaPersistence, distributedProcessing, boopickleWireProtocol)
  .settings(aecorSettings)
  .settings(scheduleSettings)

lazy val testKit = aecorModule("test-kit", "Aecor Test Kit")
  .dependsOn(core)
  .settings(aecorSettings)
  .settings(testKitSettings)

lazy val tests = aecorModule("tests", "Aecor Tests")
  .dependsOn(
    core,
    example,
    schedule,
    testKit,
    akkaPersistence,
    distributedProcessing,
    akkaGeneric,
    boopickleWireProtocol
  )
  .settings(aecorSettings)
  .settings(noPublishSettings)
  .settings(testingSettings)

lazy val example = aecorModule("example", "Aecor Example Application")
  .dependsOn(core, schedule, distributedProcessing, boopickleWireProtocol)
  .settings(aecorSettings)
  .settings(noPublishSettings)
  .settings(exampleSettings)

lazy val benchmarks = aecorModule("benchmarks", "Aecor Benchmarks")
  .dependsOn(core)
  .settings(aecorSettings)
  .settings(noPublishSettings)
  .enablePlugins(JmhPlugin)

lazy val coreSettings = Seq(
  libraryDependencies ++= Seq(
    "com.chuusai" %% "shapeless" % shapelessVersion,
    "org.typelevel" %% "cats-core" % catsVersion,
    "org.typelevel" %% "cats-effect" % catsEffectVersion,
    "com.github.mpilquist" %% "simulacrum" % simulacrumVersion,
    "io.aecor" %% "liberator" % liberatorVersion
  )
)

lazy val boopickleWireProtocolSettings = Seq(
  sources in (Compile, doc) := Nil,
  scalacOptions in (Compile, console) := Seq(),
  libraryDependencies ++= Seq(
    "io.suzaku" %% "boopickle" % boopickleVersion,
    "org.scalameta" %% "scalameta" % scalametaVersion
  )
)

lazy val scheduleSettings = commonProtobufSettings ++ Seq(
  libraryDependencies ++= Seq(
    "com.datastax.cassandra" % "cassandra-driver-extras" % cassandraDriverExtrasVersion,
    "com.google.code.findbugs" % "jsr305" % jsr305Version % Compile
  )
)

lazy val distributedProcessingSettings = commonProtobufSettings ++ Seq(
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion
  )
)

lazy val akkaPersistenceSettings = commonProtobufSettings  ++ Seq(
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-cassandra" % akkaPersistenceCassandraVersion,
    "com.github.dnvriend" %% "akka-persistence-jdbc" % akkaPersistenceJdbcVersion
  )
)

lazy val akkaGenericSettings = Seq(
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion
  )
)


lazy val exampleSettings = {
  Seq(
    resolvers += Resolver.sonatypeRepo("releases"),
    libraryDependencies ++=
      Seq(
        "io.aecor" %% "liberator" % liberatorVersion,
        "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
        "io.monix" %% "monix-reactive" % monixVersion,
        "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
        "de.heikoseeberger" %% "akka-http-circe" % akkaHttpJsonVersion,
        "io.circe" %% "circe-core" % circeVersion,
        "io.circe" %% "circe-generic" % circeVersion,
        "io.circe" %% "circe-parser" % circeVersion,
        "io.circe" %% "circe-java8" % circeVersion,
        "ch.qos.logback" % "logback-classic" % logbackVersion
      )
  )
}

lazy val testKitSettings = Seq(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-mtl-core" % "0.2.1"
  )
)

lazy val testingSettings = Seq(
  libraryDependencies ++= Seq(
    "org.scalacheck" %% "scalacheck" % scalaCheckVersion % Test,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
    "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % akkaPersistenceCassandraVersion % Test,
    "com.github.alexarchambault" %% "scalacheck-shapeless_1.13" % scalaCheckShapelessVersion % Test,
    "org.typelevel" %% "cats-testkit" % catsVersion % Test
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
    "-Ypartial-unification",
    "-Xsource:2.13"
)

lazy val warnUnusedImport = Seq(scalacOptions in (Compile, console) ~= {
  _.filterNot(Set("-Ywarn-unused-import", "-Ywarn-value-discard"))
}, scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value)

lazy val noPublishSettings = Seq(publish := (()), publishLocal := (()), publishArtifact := false)

lazy val publishSettings = Seq(
  releaseCrossBuild := true,
  releaseVersionBump := Bump.Minor,
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
    ScmInfo(url("https://github.com/notxcain/aecor"), "scm:git:git@github.com:notxcain/aecor.git")
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
    ReleaseStep(action = "sonatypeReleaseAll" :: _),
    pushChanges
  )
)

addCommandAlias("validate", ";compile;test")
