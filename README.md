
[![Build Status](https://img.shields.io/travis/notxcain/aecor/master.svg)](https://travis-ci.org/notxcain/aecor)
[![Maven Central](https://img.shields.io/maven-central/v/io.aecor/aecor-core_2.11.svg)](https://github.com/notxcain/aecor)
[![Join the chat at https://gitter.im/notxcain/aecor](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/notxcain/aecor)


# Aecor
### Typeful runtime for eventsourced behaviors

Aecor is an opinionated library to help building scalable, distributed eventsourced services written in Scala. It uses [Akka](https://github.com/akka/akka) for distribution and fault tolerance.
With the help of [Cats](https://github.com/typelevel/cats/) and [Shapeless](https://github.com/milessabin/shapeless) to reach type safety.

Aecor works on Scala 2.11 and 2.12 with Java 8.

The name `Aecor` (_lat. ocean_) is inspired by a vision of modern distributed applications, as an ocean of messages with pure behaviors floating in it.
    
### Installing Aecor

To start using Aecor Runtime add the following to your `build.sbt` file:

```scala
scalaOrganization := "org.typelevel"
libraryDependencies += "io.aecor" %% "aecor-core" % "0.13.1"
scalacOptions += "-Ypartial-unification"
```

