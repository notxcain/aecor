
![Travis build status](https://travis-ci.org/notxcain/aecor.svg?branch=master)
[![Join the chat at https://gitter.im/notxcain/akka-ddd](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/notxcain/aecor)


# Aecor
### Scalable, type safe CQRS DDD framework

Aecor is an opinionated framework for building scalable, distributed CQRS DDD services written in Scala. It uses [Akka](https://github.com/akka/akka) as a runtime for pure functional behaviors (`Any => Unit`, not even once) and clustering.
With the help of [Cats](https://github.com/typelevel/cats/) and [Shapeless](https://github.com/milessabin/shapeless) to reach type safety.

Aecor works on Scala 2.11 with Java 8.

The name `Aecor` (_lat. ocean_) is inspired by a vision of modern distributed applications, as an ocean of messages with pure behaviors floating in it.

Main goals:  
1. Type safety  
2. Pure behaviors  
3. Easy to implement Aggregate Root behaviors    
4. A simple DSL to describe Business Processes   
