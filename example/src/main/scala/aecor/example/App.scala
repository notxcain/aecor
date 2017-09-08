package aecor.example

import java.time.Clock

import aecor.data._
import aecor.distributedprocessing.{ AkkaStreamProcess, DistributedProcessing }
import aecor.effect.monix._
import aecor.example.domain.TransactionProcess.{ Input, TransactionProcessFailure }
import aecor.example.domain._
import aecor.example.domain.account.{ AccountAggregate, AccountId, EventsourcedAccountAggregate }
import aecor.example.domain.transaction.EventsourcedTransactionAggregate.tagging
import aecor.example.domain.transaction.{
  EventsourcedTransactionAggregate,
  TransactionAggregate,
  TransactionEvent,
  TransactionId
}
import aecor.runtime.akkapersistence.{ AkkaPersistenceRuntime, CassandraOffsetStore }
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

    val runtime = AkkaPersistenceRuntime(system)
    val distributedProcessing = DistributedProcessing(system)

    val cassandraSession =
      DefaultJournalCassandraSession(
        system,
        "app-session",
        CassandraOffsetStore.createTable(offsetStoreConfig)
      )

    val offsetStore = CassandraOffsetStore[Task](cassandraSession, offsetStoreConfig)

    val deployTransactions: Task[TransactionId => TransactionAggregate[Task]] =
      runtime
        .deploy("Transaction", EventsourcedTransactionAggregate.behavior[Task](taskClock), tagging)
        .map(_.andThen(TransactionAggregate.fromFunctionK))

    val deployAccounts: Task[AccountId => AccountAggregate[Task]] =
      runtime
        .deploy(
          "Account",
          EventsourcedAccountAggregate.behavior(taskClock),
          Tagging.const[AccountId](EventTag("Account"))
        )
        .map(_.andThen(AccountAggregate.fromFunctionK))

    def startTransactionProcessing(
      accounts: AccountId => AccountAggregate[Task],
      transactions: TransactionId => TransactionAggregate[Task]
    ): Task[DistributedProcessing.ProcessKillSwitch[Task]] = {
      val failure = TransactionProcessFailure.withMonadError[Task]
      val processStep: (Input) => Task[Unit] =
        TransactionProcess(transactions, accounts, failure)
      val journal = runtime
        .journal[TransactionId, TransactionEvent]
        .committable(offsetStore)
      val consumerId = ConsumerId("processing")
      val processes =
        EventsourcedTransactionAggregate.tagging.tags.map { tag =>
          AkkaStreamProcess[Task](
            journal
              .eventsByTag(tag, consumerId)
              .map(_.map(_.identified)),
            Flow[Committable[Task, Identified[TransactionId, TransactionEvent]]]
              .mapAsync(30) {
                _.traverse(processStep).runAsync
              }
              .mapAsync(1)(_.commit.runAsync)
          )
        }
      distributedProcessing.start[Task]("TransactionProcessing", processes)
    }

    def startHttpServer(
      accounts: AccountId => AccountAggregate[Task],
      transactions: TransactionId => TransactionAggregate[Task]
    ): Task[Http.ServerBinding] =
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
      transactions <- deployTransactions
      accounts <- deployAccounts
      _ <- startTransactionProcessing(accounts, transactions)
      bindResult <- startHttpServer(accounts, transactions)
      _ = system.log.info("Bind result [{}]", bindResult)
    } yield ()

    app.runAsync
    ()
  }

}
