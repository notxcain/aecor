import ReleaseTransformations._
import sbtrelease.Version.Bump

lazy val buildSettings = Seq(
  organization := "io.aecor",
  scalaVersion := "2.11.8",
  scalaOrganization := "org.typelevel",
  crossScalaVersions := Seq("2.11.8", "2.12.0")
)

lazy val akkaVersion = "2.4.17"
lazy val akkaPersistenceCassandra = "0.25"
lazy val catsVersion = "0.9.0"
lazy val logbackVersion = "1.1.7"
lazy val cassandraDriverExtrasVersion = "3.1.0"
lazy val jsr305Version = "3.0.1"

lazy val monixVersion = "2.2.1"
lazy val fs2Version = "0.9.4"
lazy val scalaCheckVersion = "1.13.4"
lazy val scalaTestVersion = "3.0.1"
lazy val scalaCheckShapelessVersion = "1.1.4"
lazy val shapelessVersion = "2.3.2"
lazy val kindProjectorVersion = "0.9.3"
lazy val paradiseVersion = "2.1.0"
lazy val simulacrumVersion = "0.10.0"

lazy val commonSettings = Seq(
  scalacOptions ++= commonScalacOptions,
  libraryDependencies ++= Seq(
    compilerPlugin("org.spire-math" %% "kind-projector" % kindProjectorVersion),
    compilerPlugin("org.scalamacros" % "paradise" % paradiseVersion cross CrossVersion.full)
  ),
  parallelExecution in Test := false,
  scalacOptions in (Compile, doc) := (scalacOptions in (Compile, doc)).value
    .filter(_ != "-Xfatal-warnings")
) ++ warnUnusedImport

lazy val aecorSettings = buildSettings ++ commonSettings ++ publishSettings

lazy val aecor = project
  .in(file("."))
  .settings(moduleName := "aecor", name := "Aecor")
  .settings(aecorSettings)
  .settings(noPublishSettings)
  .aggregate(
    core,
    akkaPersistence,
    distributedProcessing,
    example,
    schedule,
    effectMonix,
    effectFs2,
    tests
  )
  .dependsOn(core, example % "compile-internal", tests % "test-internal -> test")

lazy val core =
  project
    .settings(moduleName := "aecor-core", name := "Aecor Core")
    .settings(aecorSettings)
    .settings(coreSettings)

lazy val akkaPersistence = project
  .in(file("aecor-akka-persistence"))
  .settings(
    moduleName := "aecor-akka-persistence",
    name := "Aecor Runtime based on Akka Cluster Sharding and Persistence"
  )
  .dependsOn(core)
  .settings(aecorSettings)
  .settings(akkaPersistenceSettings)

lazy val akkaGeneric = project
  .in(file("aecor-akka-generic"))
  .settings(
    moduleName := "aecor-akka-generic",
    name := "Aecor Runtime based on Akka Cluster Sharding"
  )
  .dependsOn(core)
  .settings(aecorSettings)
  .settings(akkaPersistenceSettings)

lazy val distributedProcessing =
  project
    .in(file("distributed-processing"))
    .settings(moduleName := "aecor-distributed-processing", name := "Aecor Distributed Processing")
    .dependsOn(core)
    .settings(aecorSettings)
    .settings(distributedProcessingSettings)

lazy val schedule = project
  .dependsOn(akkaPersistence, distributedProcessing)
  .settings(moduleName := "aecor-schedule", name := "Aecor Schedule")
  .settings(aecorSettings)
  .settings(scheduleSettings)

lazy val effectMonix = project
  .in(file("aecor-monix"))
  .settings(moduleName := "aecor-monix", name := "Aecor Monix")
  .dependsOn(core)
  .settings(aecorSettings)
  .settings(effectMonixSettings)

lazy val effectFs2 = project
  .in(file("aecor-fs2"))
  .settings(moduleName := "aecor-fs2", name := "Aecor FS2")
  .dependsOn(core)
  .settings(aecorSettings)
  .settings(effectFs2Settings)

lazy val experimental = project
  .in(file("aecor-experimental"))
  .settings(moduleName := "aecor-experimental", name := "Aecor Experimental")
  .dependsOn(core)
  .settings(aecorSettings)

lazy val testKit = project
  .in(file("aecor-test-kit"))
  .settings(moduleName := "aecor-test-kit", name := "Aecor Test Kit")
  .dependsOn(core, experimental)
  .settings(aecorSettings)

lazy val tests = project
  .dependsOn(
    core,
    example,
    schedule,
    effectMonix,
    effectFs2,
    testKit,
    akkaPersistence,
    experimental,
    distributedProcessing,
    akkaGeneric
  )
  .settings(moduleName := "aecor-tests", name := "Aecor Tests")
  .settings(aecorSettings)
  .settings(noPublishSettings)
  .settings(testingSettings)

lazy val example = project
  .dependsOn(core, schedule, effectMonix, distributedProcessing, experimental)
  .settings(moduleName := "aecor-example", name := "Aecor Example Application")
  .settings(aecorSettings)
  .settings(noPublishSettings)
  .settings(exampleSettings)

lazy val coreSettings = Seq(
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
    "com.chuusai" %% "shapeless" % shapelessVersion,
    "org.typelevel" %% "cats" % catsVersion,
    "com.github.mpilquist" %% "simulacrum" % simulacrumVersion
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

lazy val akkaPersistenceSettings = Seq(
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-query-experimental" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-cassandra" % akkaPersistenceCassandra
  )
)

lazy val akkaGenericSettings = Seq(
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion
  )
)

lazy val effectMonixSettings = Seq(
  libraryDependencies ++= Seq("io.monix" %% "monix-eval" % monixVersion)
)

lazy val effectFs2Settings = Seq(libraryDependencies ++= Seq("co.fs2" %% "fs2-core" % fs2Version))

lazy val exampleSettings = {
  val circeVersion = "0.7.0"
  val akkaHttpVersion = "10.0.3"
  val akkaHttpJsonVersion = "1.11.0"
  Seq(
    resolvers ++= Seq(
      Resolver.bintrayRepo("projectseptemberinc", "maven"),
      Resolver.sonatypeRepo("releases")
    ),
    libraryDependencies ++=
      Seq(
        compilerPlugin("org.scalameta" % "paradise" % "3.0.0-M7" cross CrossVersion.full),
        "com.github.romix.akka" %% "akka-kryo-serialization" % "0.5.0",
        "io.aecor" %% "liberator" % "0.3.0",
        "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
        "io.monix" %% "monix-cats" % monixVersion,
        "io.monix" %% "monix-reactive" % monixVersion,
        "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
        "de.heikoseeberger" %% "akka-http-circe" % akkaHttpJsonVersion,
        "io.circe" %% "circe-core" % circeVersion,
        "io.circe" %% "circe-generic" % circeVersion,
        "io.circe" %% "circe-parser" % circeVersion,
        "ch.qos.logback" % "logback-classic" % logbackVersion
      )
  )
}

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
//  "-Ywarn-unused-import",
  "-Ypartial-unification"
)

lazy val warnUnusedImport = Seq(scalacOptions in (Compile, console) ~= {
  _.filterNot(Set("-Ywarn-unused-import", "-Ywarn-value-discard"))
}, scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value)

lazy val noPublishSettings = Seq(publish := (), publishLocal := (), publishArtifact := false)

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
    ReleaseStep(action = Command.process("sonatypeReleaseAll", _)),
    pushChanges
  )
)

addCommandAlias("validate", ";compile;test")
