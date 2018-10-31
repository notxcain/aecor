import ReleaseTransformations._
import sbtrelease.Version.Bump
import pl.project13.scala.sbt._

lazy val buildSettings = inThisBuild(
  Seq(
    organization := "io.aecor",
    scalaVersion := "2.12.4"
  )
)

lazy val akkaVersion = "2.5.15"
lazy val akkaPersistenceCassandraVersion = "0.61"

lazy val catsVersion = "1.4.0"
lazy val catsEffectVersion = "1.0.0"
lazy val scodecVersion = "1.10.4"
lazy val logbackVersion = "1.1.7"
lazy val cassandraDriverExtrasVersion = "3.1.0"
lazy val jsr305Version = "3.0.1"
lazy val boopickleVersion = "1.3.0"
lazy val monocleVersion = "1.5.0-cats"
lazy val fs2Version = "1.0.0"
lazy val log4catsVersion = "0.2.0-M1"

lazy val scalaCheckVersion = "1.13.4"
lazy val scalaTestVersion = "3.0.5"
lazy val scalaCheckShapelessVersion = "1.1.4"
lazy val shapelessVersion = "2.3.3"
lazy val kindProjectorVersion = "0.9.7"
lazy val scalametaVersion = "1.8.0"

// Example dependencies

lazy val circeVersion = "0.9.3"
lazy val http4sVersion = "0.20.0-M1"
lazy val scalametaParadiseVersion = "3.0.0-M10"

lazy val catsTaglessVersion = "0.1.0"

lazy val commonSettings = Seq(
  resolvers += "jitpack" at "https://jitpack.io",
  scalacOptions ++= commonScalacOptions,
  addCompilerPlugin("org.spire-math" %% "kind-projector" % kindProjectorVersion),
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
    .settings(moduleName := id, name := description)

lazy val core = aecorModule("core", "Aecor Core")
  .settings(aecorSettings)
  .settings(coreSettings)

lazy val boopickleWireProtocol =
  aecorModule("boopickle-wire-protocol", "Aecor Boopickle Wire Protocol derivation")
    .dependsOn(core)
    .settings(aecorSettings)
    .settings(boopickleWireProtocolSettings)

lazy val akkaPersistence = aecorModule(
  "akka-persistence-runtime",
  "Aecor Runtime based on Akka Cluster Sharding and Persistence"
).dependsOn(core)
  .settings(aecorSettings)
  .settings(akkaPersistenceSettings)

lazy val akkaGeneric =
  aecorModule("akka-cluster-runtime", "Aecor Runtime based on Akka Cluster Sharding")
    .dependsOn(core)
    .dependsOn(boopickleWireProtocol % "test->compile")
    .settings(aecorSettings)
    .settings(commonTestSettings)
    .settings(akkaGenericSettings)

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
    schedule,
    testKit,
    akkaPersistence,
    distributedProcessing,
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
    "org.typelevel" %% "cats-tagless-macros" % catsTaglessVersion,
    "com.chuusai" %% "shapeless" % shapelessVersion,
    "org.typelevel" %% "cats-core" % catsVersion,
    "org.typelevel" %% "cats-effect" % catsEffectVersion,
    "org.scodec" %% "scodec-bits" % "1.1.6",
    "org.scodec" %% "scodec-core" % "1.10.3"
  )
)

lazy val boopickleWireProtocolSettings = Seq(
  addCompilerPlugin(
    "org.scalameta" % "paradise" % scalametaParadiseVersion cross CrossVersion.patch
  ),
  sources in (Compile, doc) := Nil,
  scalacOptions in (Compile, console) := Seq(),
  libraryDependencies ++= Seq(
    "io.suzaku" %% "boopickle" % boopickleVersion,
    "org.scalameta" %% "scalameta" % scalametaVersion
  )
)

lazy val scheduleSettings = commonProtobufSettings ++ Seq(
  sources in (Compile, doc) := Nil,
  addCompilerPlugin(
    "org.scalameta" % "paradise" % scalametaParadiseVersion cross CrossVersion.patch
  ),
  libraryDependencies ++= Seq(
    "com.datastax.cassandra" % "cassandra-driver-extras" % cassandraDriverExtrasVersion,
    "com.google.code.findbugs" % "jsr305" % jsr305Version % Compile
  )
)

lazy val distributedProcessingSettings = commonProtobufSettings ++ Seq(
  libraryDependencies ++= Seq("com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion)
)


lazy val akkaPersistenceSettings = commonProtobufSettings ++ Seq(
  libraryDependencies ++= Seq(
    "co.fs2" %% "fs2-core" % fs2Version,
    "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-cassandra" % akkaPersistenceCassandraVersion
  )
)

lazy val akkaGenericSettings = commonProtobufSettings ++ Seq(
  libraryDependencies ++= Seq("com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion)
)

lazy val exampleSettings = {
  Seq(
    addCompilerPlugin(
      "org.scalameta" % "paradise" % scalametaParadiseVersion cross CrossVersion.patch
    ),
    resolvers += Resolver.sonatypeRepo("releases"),
    resolvers += "krasserm at bintray" at "http://dl.bintray.com/krasserm/maven",
    libraryDependencies ++=
      Seq(
        "com.github.krasserm" %% "streamz-converter" % "0.10-M1",
        "co.fs2" %% "fs2-core" % "1.0.0",
        "org.typelevel" %% "cats-mtl-core" % "0.4.0",
        "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
        "org.http4s" %% "http4s-dsl" % http4sVersion,
        "org.http4s" %% "http4s-blaze-server" % http4sVersion,
        "org.http4s" %% "http4s-circe" % http4sVersion,
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
    "org.typelevel" %% "cats-mtl-core" % "0.4.0",
    "com.github.julien-truffaut" %% "monocle-core" % monocleVersion,
    "com.github.julien-truffaut" %% "monocle-macro" % monocleVersion
  )
)

lazy val testingSettings = Seq(
  addCompilerPlugin(
    "org.scalameta" % "paradise" % scalametaParadiseVersion cross CrossVersion.patch
  ),
  libraryDependencies ++= Seq(
    "io.circe" %% "circe-core" % circeVersion,
    "io.circe" %% "circe-generic" % circeVersion,
    "io.circe" %% "circe-parser" % circeVersion,
    "io.circe" %% "circe-java8" % circeVersion,
    "org.scalacheck" %% "scalacheck" % scalaCheckVersion % Test,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
    "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % akkaPersistenceCassandraVersion % Test,
    "com.github.alexarchambault" %% "scalacheck-shapeless_1.13" % scalaCheckShapelessVersion % Test,
    "org.typelevel" %% "cats-testkit" % catsVersion % Test
  )
)

lazy val commonTestSettings =
  Seq(
    addCompilerPlugin(
      "org.scalameta" % "paradise" % scalametaParadiseVersion cross CrossVersion.patch
    ),
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

lazy val warnUnusedImport = Seq(
  scalacOptions in (Compile, console) --= Seq("-Ywarn-unused:imports", "-Xfatal-warnings")
)

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
