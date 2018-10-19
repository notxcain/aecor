package aecor.example

import aecor.data._
import aecor.distributedprocessing.DistributedProcessing
import aecor.example.account.{ AccountRoute, Accounts }
import aecor.example.common.Timestamp
import aecor.example.process.{ FS2QueueProcess, TransactionProcessor }
import aecor.example.transaction.transaction.Transactions
import aecor.example.transaction.{ TransactionEvent, TransactionId, TransactionRoute }
import aecor.runtime.akkapersistence.readside.CassandraOffsetStore
import aecor.runtime.akkapersistence.{ AkkaPersistenceRuntime, CassandraJournalAdapter }
import aecor.util.JavaTimeClock
import akka.actor.ActorSystem
import akka.persistence.cassandra.DefaultJournalCassandraSession
import akka.stream.{ ActorMaterializer, Materializer }
import cats.effect._
import com.typesafe.config.ConfigFactory
import streamz.converter._
import cats.implicits._

import scala.concurrent.duration._
import fs2._
import org.http4s.server.Server
import org.http4s.server.blaze.BlazeBuilder

object App extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = IO.suspend {
    val config = ConfigFactory.load()
    implicit val system: ActorSystem = ActorSystem(config.getString("cluster.system-name"))
    system.registerOnTermination {
      System.exit(1)
    }
    implicit val materializer: Materializer = ActorMaterializer()

    val taskClock = JavaTimeClock.systemUTC[IO]

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
        val sources = transaction.EventsourcedAlgebra.tagging.tags.map { tag =>
          journal
            .eventsByTag(tag, consumerId)
            .toStream[IO]()
        }
        FS2QueueProcess.create(sources).flatMap {
          case (stream, processes) =>
            val run = distributedProcessing.start[IO]("TransactionProcessing", processes)
            run.flatMap { ks =>
              stream
                .map { s =>
                  val run = s
                    .mapAsync(30)(_.traverse(processor.process(_)))
                    .evalMap(_.commit)
                    .compile
                    .drain
                  Stream.retry(run, 1.second, identity, Int.MaxValue)
                }
                .parJoin(processes.size)
                .compile
                .drain
                .start
                .as(ks)
            }
        }
      }

    def startHttpServer(accounts: Accounts[IO],
                        transactions: Transactions[IO]): Resource[IO, Server[IO]] = {
      val transactionService: transaction.TransactionService[IO] =
        transaction.DefaultTransactionService(transactions)
      val accountService: account.AccountService[IO] = account.DefaultAccountService(accounts)

      BlazeBuilder[IO]
        .bindHttp(config.getInt("http.port"), config.getString("http.interface"))
        .mountService(TransactionRoute(transactionService), "/")
        .mountService(AccountRoute(accountService), "/")
        .resource
    }

    for {
      transactions <- transaction.deployment.deploy[IO](runtime, taskClock)
      accounts <- account.deployment.deploy[IO](runtime, taskClock)
      _ <- startTransactionProcessing(accounts, transactions)
      _ <- startHttpServer(accounts, transactions).use { _ =>
            IO.fromFuture(IO(system.whenTerminated))
          }
    } yield ExitCode.Success
  }

}
