import ReleaseTransformations._
import sbtrelease.Version.Bump
import pl.project13.scala.sbt._
import Dependencies.versions

lazy val buildSettings = inThisBuild(Seq(organization := "io.aecor", scalaVersion := "2.12.4"))

lazy val commonSettings = Seq(
  resolvers += "jitpack" at "https://jitpack.io",
  scalacOptions ++= commonScalacOptions,
  addCompilerPlugin("org.spire-math" %% "kind-projector" % versions.kindProjector),
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
  .dependsOn(core, schedule, testKit, akkaPersistence, distributedProcessing, boopickleWireProtocol)
  .settings(aecorSettings)
  .settings(noPublishSettings)
  .settings(testingSettings)

lazy val benchmarks = aecorModule("benchmarks", "Aecor Benchmarks")
  .dependsOn(core)
  .settings(aecorSettings)
  .settings(noPublishSettings)
  .enablePlugins(JmhPlugin)

lazy val coreSettings = Seq(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-tagless-macros" % versions.catsTagless,
    "com.chuusai" %% "shapeless" % versions.shapeless,
    "org.typelevel" %% "cats-core" % versions.cats,
    "org.typelevel" %% "cats-effect" % versions.catsEffect,
    "org.scodec" %% "scodec-bits" % versions.scodec_bits,
    "org.scodec" %% "scodec-core" % versions.scodec,
    "io.circe" %% "circe-core" % versions.circe,
    "io.circe" %% "circe-generic" % versions.circe,
    "io.circe" %% "circe-parser" % versions.circe,
    "io.circe" %% "circe-java8" % versions.circe,
  )
)

lazy val boopickleWireProtocolSettings = Seq(
  addCompilerPlugin(
    "org.scalameta" % "paradise" % versions.scalametaParadise cross CrossVersion.patch
  ),
  sources in (Compile, doc) := Nil,
  scalacOptions in (Compile, console) := Seq(),
  libraryDependencies ++= Seq(
    "io.suzaku" %% "boopickle" % versions.boopickle,
    "org.scalameta" %% "scalameta" % versions.scalameta
  )
)

lazy val scheduleSettings = commonProtobufSettings ++ Seq(
  sources in (Compile, doc) := Nil,
  addCompilerPlugin(
    "org.scalameta" % "paradise" % versions.scalametaParadise cross CrossVersion.patch
  ),
  libraryDependencies ++= Seq(
    "com.datastax.cassandra" % "cassandra-driver-extras" % versions.cassandraDriverExtras,
    "com.google.code.findbugs" % "jsr305" % versions.jsr305 % Compile
  )
)

lazy val distributedProcessingSettings = commonProtobufSettings ++ Seq(
  libraryDependencies ++= Seq("com.typesafe.akka" %% "akka-cluster-sharding" % versions.akka)
)

lazy val akkaPersistenceSettings = commonProtobufSettings ++ Seq(
  libraryDependencies ++= Seq(
    "co.fs2" %% "fs2-core" % versions.fs2,
    "com.typesafe.akka" %% "akka-cluster-sharding" % versions.akka,
    "com.typesafe.akka" %% "akka-persistence" % versions.akka,
    "com.typesafe.akka" %% "akka-persistence-query" % versions.akka,
    "com.typesafe.akka" %% "akka-persistence-cassandra" % versions.akkaPersistenceCassandra
  )
)

lazy val akkaGenericSettings = commonProtobufSettings ++ Seq(
  libraryDependencies ++= Seq("com.typesafe.akka" %% "akka-cluster-sharding" % versions.akka)
)

lazy val testKitSettings = Seq(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-mtl-core" % versions.cats_mtl_core,
    "com.github.julien-truffaut" %% "monocle-core" % versions.monocle,
    "com.github.julien-truffaut" %% "monocle-macro" % versions.monocle
  )
)

lazy val testingSettings = Seq(
  addCompilerPlugin(
    "org.scalameta" % "paradise" % versions.scalametaParadise cross CrossVersion.patch
  ),
  libraryDependencies ++= Seq(
    "io.circe" %% "circe-core" % versions.circe,
    "io.circe" %% "circe-generic" % versions.circe,
    "io.circe" %% "circe-parser" % versions.circe,
    "io.circe" %% "circe-java8" % versions.circe,
    "org.scalacheck" %% "scalacheck" % versions.scalaCheck % Test,
    "org.scalatest" %% "scalatest" % versions.scalaTest % Test,
    "com.typesafe.akka" %% "akka-testkit" % versions.akka % Test,
    "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % versions.akkaPersistenceCassandra % Test,
    "com.github.alexarchambault" %% "scalacheck-shapeless_1.13" % versions.scalaCheckShapeless % Test,
    "org.typelevel" %% "cats-testkit" % versions.cats % Test
  )
)

lazy val commonTestSettings =
  Seq(
    addCompilerPlugin(
      "org.scalameta" % "paradise" % versions.scalametaParadise cross CrossVersion.patch
    ),
    libraryDependencies ++= Seq(
      "org.scalacheck" %% "scalacheck" % versions.scalaCheck % Test,
      "org.scalatest" %% "scalatest" % versions.scalaTest % Test,
      "com.typesafe.akka" %% "akka-testkit" % versions.akka % Test,
      "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % versions.akkaPersistenceCassandra % Test,
      "com.github.alexarchambault" %% "scalacheck-shapeless_1.13" % versions.scalaCheckShapeless % Test,
      "org.typelevel" %% "cats-testkit" % versions.cats % Test
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
    //val nexus = "https://api.bintray.com/aecor/aecor/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },
  //credentials += Credentials(Path.userHome / ".sbt" / ".credentials"),
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
