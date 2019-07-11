# Kafka Distirbuted Processing (KDP)
 
It uses Kafka partition assignment machinery to distribute a number of processes over a cluster of nodes.

## What you need
1. Running highly available Kafka cluster
1. A technical topic with a proper number of partitions
1. A list `List[F[Unit]]` of processes to distribute

## How it works 
Each application node with a running KDP instance gets a number of partitions assigned.  
The process list is sliced based on total number of partitions in topic.  
A slice corresponding to assigned partition starts.  
When partition is revoked, e.g. some app node failed or was added, 
it is guaranteed that assigned process list slice is stopped first and then restarted on another node.
Each process should properly handle cancellation to free any resource and stop any processing.
It is guaranteed that there would be only one instance of process from the list running at any given time.

KDP is suitable for running a terminating processes i.e. it will not restart them, and by defaul will also fail if any process fails.  
If you need (and most time you do) supervision you may wrap each of your processes using either provided [Supervision](./src/main/scala/aecor/kafkadistributedprocessing/Supervision.scala) or implement your own strategy.
 
 

## How to run
build.sbt
```scala
libraryDependencies += "io.aecor" %% "kafka-distributed-processing" % aecorVersion
```
Your app
```scala

import aecor.kafkadistributedprocessing._
import cats.effect.{ ExitCode, IO, IOApp }
import fs2._
import scala.concurrent.duration._
import cats.implicits._

object App extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val processes = (1 to 20).map { idx =>
      Stream.eval(IO(println(idx)) >> timer.sleep(1.second)).repeat.compile.drain
    }.toList
    val settings = DistributedProcessingSettings(
      brokers = Set("localhost:9092"),
      topicName = "distributed-processing"
    )
    
    // You can reuse `processing` to start multiple process lists.
    val processing = DistributedProcessing(settings)

    processing.start(name = "Printers", processes).as(ExitCode.Success)
  }
}

```




 