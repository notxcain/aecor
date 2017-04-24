package aecor.example

import java.time.Clock

import aecor.data.{ Committable, Correlation, EventTag, Tagging }
import aecor.distributedprocessing.{ DistributedProcessing, StreamingProcess }
import aecor.effect.Async.ops._
import aecor.effect.monix._
import aecor.example.domain.TransactionProcess.{ Input, TransactionProcessFailure }
import aecor.example.domain._
import aecor.example.domain.account.{
  AccountAggregate,
  AccountEvent,
  EventsourcedAccountAggregate
}
import aecor.example.domain.transaction.{
  EventsourcedTransactionAggregate,
  TransactionAggregate,
  TransactionEvent
}
import aecor.runtime.akkapersistence.{
  AkkaPersistenceRuntime,
  CassandraAggregateJournal,
  CassandraOffsetStore,
  JournalEntry
}
import io.aecor.liberator.syntax._
import aecor.streaming.ConsumerId
import akka.NotUsed

import scala.collection.immutable._
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{ complete, get, path, _ }
import akka.persistence.cassandra.DefaultJournalCassandraSession
import akka.stream.scaladsl.{ Flow, Sink }
import akka.stream.{ ActorMaterializer, Materializer }
import cats.~>
import com.typesafe.config.ConfigFactory
import aecor.distributedprocessing.DistributedProcessing.RunningProcess
import io.aecor.distributedprocessing.StreamingProcess
import io.aecor.liberator.{ Extract, Term }
import io.aecor.liberator.data.ProductKK
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.cats._
import shapeless.Lazy

object App {
  def main(args: Array[String]): Unit = {
    implicit def liberatorRightExtractInstance[F[_[_]], G[_[_]], H[_[_]]](
      implicit extract: Lazy[Extract[G, F]]
    ): Extract[ProductKK[H, G, ?[_]], F] =
      Extract.liberatorRightExtractInstance(extract.value)

    val config = ConfigFactory.load()
    implicit val system: ActorSystem = ActorSystem(config.getString("cluster.system-name"))
    system.registerOnTermination {
      System.exit(1)
    }
    implicit val materializer: Materializer = ActorMaterializer()

    val offsetStoreConfig =
      CassandraOffsetStore.Config(config.getString("cassandra-journal.keyspace"))

    val cassandraSession =
      DefaultJournalCassandraSession(
        system,
        "app-session",
        CassandraOffsetStore.createTable(offsetStoreConfig)
      )

    val startTransactions: Task[TransactionAggregate[Task]] =
      AkkaPersistenceRuntime[Task](system)
        .start(
          "Transaction",
          TransactionAggregate.toFunctionK(new EventsourcedTransactionAggregate[Task]),
          Correlation[TransactionAggregate.TransactionAggregateOp](_.transactionId.value),
          Tagging.partitioned(20, EventTag[TransactionEvent]("Transaction"))(_.transactionId.value)
        )
        .map(TransactionAggregate.fromFunctionK)

    val startAccounts: Task[AccountAggregate[Task]] =
      AkkaPersistenceRuntime[Task](system)
        .start(
          "Account",
          AccountAggregate.toFunctionK(new EventsourcedAccountAggregate[Task]),
          Correlation[AccountAggregate.AccountAggregateOp](_.accountId.value),
          Tagging.partitioned(20, EventTag[AccountEvent]("Account"))(_.accountId.value)
        )
        .map(AccountAggregate.fromFunctionK)

    def startTransactionProcessing(
      accounts: AccountAggregate[Task],
      transactions: TransactionAggregate[Task]
    ): Task[DistributedProcessing.ProcessKillSwitch[Task]] = {
      val failure = TransactionProcessFailure.withMonadError[Task]

      val impl = transactions :&: accounts :&: failure

      val process: (Input) => Task[Unit] =
        TransactionProcess[Term[ProductKK[TransactionAggregate,
                                          ProductKK[AccountAggregate,
                                                    TransactionProcessFailure,
                                                    ?[_]],
                                          ?[_]], ?]].andThen(_(impl))

      val transactionEventJournal =
        CassandraAggregateJournal[TransactionEvent](system)

      val processes =
        DistributedProcessing.distribute[Task](20) { i =>
          StreamingProcess[Task](
            transactionEventJournal
              .committableEventsByTag(
                CassandraOffsetStore[Task](cassandraSession, offsetStoreConfig),
                EventTag[TransactionEvent](s"Transaction$i"),
                ConsumerId("processing")
              )
              .map(_.map(_.event)),
            Flow[Committable[Task, TransactionEvent]]
              .mapAsync(30) {
                _.traverse(process).runAsync
              }
              .mapAsync(1)(_.commit.runAsync)
          )
        }

      DistributedProcessing(system).start[Task]("TransactionProcessing", processes)
    }

    def startHttpServer(accounts: AccountAggregate[Task],
                        transactions: TransactionAggregate[Task]): Task[Http.ServerBinding] =
      Task.defer {
        Task.fromFuture {
          val transactionEndpoint =
            new TransactionEndpoint(transactions, Logging(system, classOf[TransactionEndpoint]))
          val accountApi = new AccountEndpoint(accounts)

          val route = path("check") {
            get {
              complete(StatusCodes.OK)
            }
          } ~
            TransactionEndpoint.route(transactionEndpoint) ~
            AccountEndpoint.route(accountApi)

          Http()
            .bindAndHandle(route, config.getString("http.interface"), config.getInt("http.port"))
        }
      }

    val app = for {
      transactions <- startTransactions
      accounts <- startAccounts
      _ <- startTransactionProcessing(accounts, transactions)
      bindResult <- startHttpServer(accounts, transactions)
      _ = system.log.info("Bind result [{}]", bindResult)
    } yield ()

    app.runAsync
    ()
  }

}
