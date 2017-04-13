package aecor.example

import java.time.Clock

import aecor.data.{ Committable, Tagging }
import aecor.example.domain.CardAuthorizationAggregateEvent.CardAuthorizationCreated
import aecor.example.domain._
import aecor.streaming._
import akka.NotUsed
import akka.actor.{ Actor, ActorLogging, ActorSystem, Props }
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.persistence.cassandra.DefaultJournalCassandraSession
import akka.stream.scaladsl.{ Flow, Sink }
import akka.stream.{ ActorMaterializer, Materializer }
import cats.~>
import com.typesafe.config.Config
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import aecor.effect.Async.ops._
import monix.cats._
import aecor.effect.monix._
import aecor.runtime.akkapersistence.{
  AkkaPersistenceRuntime,
  CassandraAggregateJournal,
  CassandraOffsetStore,
  JournalEntry
}

object AppActor {
  def props: Props = Props(new AppActor)
}
