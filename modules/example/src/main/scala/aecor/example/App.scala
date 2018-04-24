package aecor.example

import java.time.Clock

import aecor.data._
import aecor.distributedprocessing.{ AkkaStreamProcess, DistributedProcessing }
import aecor.example.domain.TransactionProcess.TransactionProcessFailure
import aecor.example.domain._
import aecor.example.domain.account.{ Account, AccountId, EventsourcedAccount }
import aecor.example.domain.transaction.EventsourcedTransactionAggregate.tagging
import aecor.example.domain.transaction.{
  EventsourcedTransactionAggregate,
  TransactionAggregate,
  TransactionEvent,
  TransactionId
}
import aecor.runtime.akkapersistence.{ AkkaPersistenceRuntime, CassandraJournalAdapter }
import aecor.runtime.akkapersistence.readside.CassandraOffsetStore
import aecor.util.JavaTimeClock
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{ complete, get, path, _ }
import akka.http.scaladsl.server.Route
import akka.persistence.cassandra.DefaultJournalCassandraSession
import akka.stream.{ ActorMaterializer, Materializer }
import com.typesafe.config.ConfigFactory
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

    val runtime = AkkaPersistenceRuntime(system, CassandraJournalAdapter(system))
    val distributedProcessing = DistributedProcessing(system)

    val cassandraSession =
      DefaultJournalCassandraSession(
        system,
        "app-session",
        CassandraOffsetStore.createTable(offsetStoreConfig)
      )

    val offsetStore = CassandraOffsetStore[Task](cassandraSession, offsetStoreConfig)

    val metaProvider = taskClock.instant.map(Timestamp(_))

    val deployTransactions: Task[TransactionId => TransactionAggregate[Task]] =
      runtime
        .deploy(
          "Transaction",
          EventsourcedTransactionAggregate.behavior.liftEnrich(metaProvider),
          tagging
        )

    val deployAccounts: Task[AccountId => Account[Task]] =
      runtime
        .deploy(
          "Account",
          EventsourcedAccount.behavior.liftEnrich(metaProvider),
          Tagging.const[AccountId](EventTag("Account"))
        )

    def startTransactionProcessing(
      accounts: AccountId => Account[Task],
      transactions: TransactionId => TransactionAggregate[Task]
    ): Task[DistributedProcessing.KillSwitch[Task]] = {
      val failure = TransactionProcessFailure.withMonadError[Task]
      val processor =
        TransactionProcess(transactions, accounts, failure)
      val journal = runtime
        .journal[TransactionId, Enriched[Timestamp, TransactionEvent]]
        .committable(offsetStore)
      val consumerId = ConsumerId("processing")
      val processes =
        EventsourcedTransactionAggregate.tagging.tags.map { tag =>
          AkkaStreamProcess[Task](
            journal
              .eventsByTag(tag, consumerId)
              .mapAsync(30) {
                _.traverse(processor.process(_)).runAsync
              }
              .mapAsync(1)(_.commit.runAsync)
          )
        }
      distributedProcessing.start[Task]("TransactionProcessing", processes)
    }

    def startHttpServer(
      accounts: AccountId => Account[Task],
      transactions: TransactionId => TransactionAggregate[Task]
    ): Task[Http.ServerBinding] =
      Task.defer {
        Task.fromFuture {
          val transactionEndpoint =
            new TransactionEndpoint(transactions, Logging(system, classOf[TransactionEndpoint]))
          val accountApi = new AccountEndpoint(accounts)

          val route: Route = path("check") {
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
