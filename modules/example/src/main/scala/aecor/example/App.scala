package aecor.example

import java.time.Clock

import aecor.data._
import aecor.distributedprocessing.{AkkaStreamProcess, DistributedProcessing}
import aecor.example.account.{AccountRoute, Accounts}
import aecor.example.common.Timestamp
import aecor.example.process.TransactionProcessor
import aecor.example.transaction.transaction.Transactions
import aecor.example.transaction.{TransactionEvent, TransactionId, TransactionRoute}
import aecor.runtime.akkapersistence.readside.CassandraOffsetStore
import aecor.runtime.akkapersistence.{AkkaPersistenceRuntime, CassandraJournalAdapter}
import aecor.util.JavaTimeClock
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{complete, get, path, _}
import akka.http.scaladsl.server.Route
import akka.persistence.cassandra.DefaultJournalCassandraSession
import akka.stream.{ActorMaterializer, Materializer}
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

    def startTransactionProcessing(
      accounts: Accounts[IO],
      transactions: Transactions[IO]
    ): IO[DistributedProcessing.KillSwitch[IO]] =
      createOffsetStore.flatMap { offsetStore =>
        val processor =
          TransactionProcessor(transactions, accounts)
        val journal = runtime
          .journal[TransactionId, Enriched[Timestamp, TransactionEvent]]
          .committable(offsetStore)
        val consumerId = ConsumerId("processing")
        val processes =
          transaction.EventsourcedAlgebra.tagging.tags.map { tag =>
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
      accounts: Accounts[IO],
      transactions: Transactions[IO]
    ): IO[Http.ServerBinding] = {
        val transactionService: transaction.TransactionService[IO] = transaction.DefaultTransactionService(transactions)
        val accountService: account.AccountService[IO] = account.DefaultAccountService(accounts)

        val route: Route = path("check") {
          get {
            complete(StatusCodes.OK)
          }
        } ~
          TransactionRoute(transactionService) ~
          AccountRoute(accountService)
        HttpServer.start[IO](route, config.getString("http.interface"), config.getInt("http.port"))
      }

    for {
      transactions <- transaction.deployment.deploy[IO](runtime, taskClock)
      accounts <- account.deployment.deploy[IO](runtime, taskClock)
      _ <- startTransactionProcessing(accounts, transactions)
      bindResult <- startHttpServer(accounts, transactions)
      _ = system.log.info("Bind result [{}]", bindResult)
      _ <- IO.fromFuture(IO(system.whenTerminated))
    } yield ExitCode.Success
  }

}
