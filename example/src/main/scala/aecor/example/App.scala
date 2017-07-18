package aecor.example

import java.time.Clock

import aecor.data._
import aecor.distributedprocessing.{ AkkaStreamProcess, DistributedProcessing }
import aecor.effect.monix._
import aecor.example.domain.TransactionProcess.{ Input, TransactionProcessFailure }
import aecor.example.domain._
import aecor.example.domain.account.{ AccountAggregate, AccountEvent, EventsourcedAccountAggregate }
import aecor.example.domain.transaction.{
  EventsourcedTransactionAggregate,
  TransactionAggregate,
  TransactionEvent
}
import aecor.runtime.akkapersistence.{ AkkaPersistenceRuntime2, CassandraOffsetStore }
import aecor.util.JavaTimeClock
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{ complete, get, path, _ }
import akka.persistence.cassandra.DefaultJournalCassandraSession
import akka.stream.scaladsl.Flow
import akka.stream.{ ActorMaterializer, Materializer }
import com.typesafe.config.ConfigFactory
import monix.cats._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

object App {
  def main(args: Array[String]): Unit = {

    val config = ConfigFactory.load()
    implicit val system: ActorSystem = ActorSystem(config.getString("cluster.system-name"))
    system.registerOnTermination {
      System.exit(1)
    }
    implicit val materializer: Materializer = ActorMaterializer()

    val taskClock = JavaTimeClock[Task](Clock.systemUTC())

    val offsetStoreConfig =
      CassandraOffsetStore.Config(config.getString("cassandra-journal.keyspace"))

    val cassandraSession =
      DefaultJournalCassandraSession(
        system,
        "app-session",
        CassandraOffsetStore.createTable(offsetStoreConfig)
      )

    val transactionAggregateRuntime = AkkaPersistenceRuntime2(
      system,
      "Transaction",
      Correlation[TransactionAggregate.TransactionAggregateOp](_.transactionId.value),
      EventsourcedTransactionAggregate.behavior[Task](taskClock),
      Tagging.partitioned[TransactionEvent](20, EventTag("Transaction"))(_.transactionId.value)
    )

    val startTransactions: Task[TransactionAggregate[Task]] =
      transactionAggregateRuntime.start
        .map(TransactionAggregate.fromFunctionK)

    val offsetStore = CassandraOffsetStore[Task](cassandraSession, offsetStoreConfig)

    val accountAggregateRuntime = AkkaPersistenceRuntime2(
      system,
      "Account",
      Correlation[AccountAggregate.AccountAggregateOp](_.accountId.value),
      EventsourcedAccountAggregate.behavior[Task](taskClock),
      Tagging.partitioned[AccountEvent](20, EventTag("Account"))(_.accountId.value)
    )

    val startAccounts: Task[AccountAggregate[Task]] =
      accountAggregateRuntime.start
        .map(AccountAggregate.fromFunctionK)

    def startTransactionProcessing(
      accounts: AccountAggregate[Task],
      transactions: TransactionAggregate[Task]
    ): Task[DistributedProcessing.ProcessKillSwitch[Task]] = {
      val failure = TransactionProcessFailure.withMonadError[Task]

      val process: (Input) => Task[Unit] =
        TransactionProcess(transactions, accounts, failure)

      val processes =
        DistributedProcessing.distribute[Task](20) { i =>
          AkkaStreamProcess[Task](
            transactionAggregateRuntime.journal
              .committable(offsetStore)
              .eventsByTag(EventTag(s"Transaction$i"), ConsumerId("processing"))
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
