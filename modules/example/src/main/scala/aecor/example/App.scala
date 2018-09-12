package aecor.example

import java.time.Clock

import aecor.data._
import aecor.distributedprocessing.{ AkkaStreamProcess, DistributedProcessing }
import aecor.example.account.http.{Service => AccountService}
import aecor.example.domain.TransactionProcess.RaiseError
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
import cats.data.ReaderT
import cats.effect.concurrent.Deferred
import cats.implicits._
import cats.effect._
import com.typesafe.config.ConfigFactory

object App extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = IO.suspend {
    val config = ConfigFactory.load()
    implicit val system: ActorSystem = ActorSystem(config.getString("cluster.system-name"))
    system.registerOnTermination {
      System.exit(1)
    }
    implicit val materializer: Materializer = ActorMaterializer()

    val taskClock = JavaTimeClock[IO](Clock.systemUTC())

    val offsetStoreConfig =
      CassandraOffsetStore.Queries(config.getString("cassandra-journal.keyspace"))

    val runtime = AkkaPersistenceRuntime(system, CassandraJournalAdapter(system))
    val distributedProcessing = DistributedProcessing(system)

    val createCassandraSession =
      DefaultJournalCassandraSession[IO](
        system,
        "app-session",
        CassandraOffsetStore[IO].createTable(offsetStoreConfig)
      )

    val createOffsetStore =
      createCassandraSession.map(CassandraOffsetStore[IO](_, offsetStoreConfig))

    val metaProvider = taskClock.instant.map(Timestamp(_))

    val deployTransactions: IO[TransactionId => TransactionAggregate[IO]] =
      runtime
        .deploy(
          "Transaction",
          EventsourcedTransactionAggregate.behavior[IO].en(metaProvider),
          tagging
        )

    val deployAccounts: IO[AccountId => Account[IO]] =
      runtime
        .deploy(
          "Account",
          EventsourcedAccount.behavior.liftEnrich(metaProvider),
          Tagging.const[AccountId](EventTag("Account"))
        )

    def startTransactionProcessing(
      accounts: AccountId => Account[IO],
      transactions: TransactionId => TransactionAggregate[IO]
    ): IO[DistributedProcessing.KillSwitch[IO]] =
      createOffsetStore.flatMap { offsetStore =>
        val failure = RaiseError.withMonadError[IO]
        val processor =
          TransactionProcess(transactions, accounts, failure)
        val journal = runtime
          .journal[TransactionId, Enriched[Timestamp, TransactionEvent]]
          .committable(offsetStore)
        val consumerId = ConsumerId("processing")
        val processes =
          EventsourcedTransactionAggregate.tagging.tags.map { tag =>
            AkkaStreamProcess[IO](
              journal
                .eventsByTag(tag, consumerId)
                .mapAsync(30) {
                  _.traverse(processor.process(_)).unsafeToFuture()
                }
                .mapAsync(1)(_.commit.unsafeToFuture())
            )
          }
        distributedProcessing.start[IO]("TransactionProcessing", processes)
      }

    def startHttpServer(
      accounts: AccountId => Account[IO],
      transactions: TransactionId => TransactionAggregate[IO]
    ): IO[Http.ServerBinding] =
      IO.fromFuture {
        IO {
          val transactionEndpoint =
            new TransactionEndpoint(transactions, Logging(system, classOf[TransactionEndpoint[IO]]))
          val accountApi = new AccountService(accounts)

          val route: Route = path("check") {
            get {
              complete(StatusCodes.OK)
            }
          } ~
            TransactionEndpoint.route(transactionEndpoint) ~
            AccountService.route(accountApi)

          Http()
            .bindAndHandle(route, config.getString("http.interface"), config.getInt("http.port"))
        }
      }

    for {
      transactions <- deployTransactions
      accounts <- deployAccounts
      _ <- startTransactionProcessing(accounts, transactions)
      bindResult <- startHttpServer(accounts, transactions)
      _ = system.log.info("Bind result [{}]", bindResult)
      _ <- IO.fromFuture(IO(system.whenTerminated))
    } yield ExitCode.Success
  }

}
