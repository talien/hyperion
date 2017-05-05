import org.scalatest._
import hyperion._
import akka.actor._
import akka.testkit._
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.util._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global

class TestPipeCreatorCase extends TestKit(ActorSystem("HyperionTest1")) with ImplicitSender
  with WordSpecLike with MustMatchers with BeforeAndAfterAll {

  override def afterAll {
    Registry.reset
    shutdown()
  }

  "PipeCreator" must {
    implicit val timeout = Timeout(10000 millis)
    "be able to create and query Tail objects" in {
      val pipeManager = system.actorOf(Props(new PipeCreator(system, new PipeFactory("HyperionTest1"))), "creator")
      val options = NodeProperty("almafa", 10, 10, PipeOptions("tail", "tail", Map("backlog" -> "1")))
      pipeManager ! Create(options)
      system.actorSelection("akka://HyperionTest1/user/pipe_almafa") ! Message.empty
      val result = Await.result(pipeManager ? TailQuery("almafa"), timeout.duration).asInstanceOf[List[Message]]
      assert(result == List[Message](Message.empty))
    }

    "be able to accept UploadConfig messages" in {
      val pipeManager = system.actorOf(Props(new PipeCreator(system, new PipeFactory("HyperionTest1"))), "creator2")
      val config = UploadConfig(
        Config(
          List[NodeProperty](
            NodeProperty("almaid", 0, 0,
              PipeOptions("alma", "source",
                Map[String, String](("port", "10000"))
              )
            ),
            NodeProperty("korteid", 0, 0,
              PipeOptions("korte", "tail",
                Map[String, String](("backlog", "10"))
              )
            )
          ),
          List[Connection](Connection("almaid", "korteid"))
        )
      )
      val res = pipeManager ? config
      val result = Await.result(res, timeout.duration)
      result match {
        case Success(_) => ;
        case Failure(e) => fail("UploadConfig did not succeeded! error:" + e.getMessage())
      }

    }

    "be able to accept UploadConfig message and fail on lingering connection" in {
      val pipeManager = system.actorOf(Props(new PipeCreator(system, new PipeFactory("HyperionTest1"))), "creator3")
      val config = UploadConfig(
        Config(
          List[NodeProperty](
            NodeProperty("almaid", 0, 0,
              PipeOptions("alma", "source",
                Map[String, String](("port", "10000"))
              )
            ),
            NodeProperty("korteid", 0, 0,
              PipeOptions("korte", "tail",
                Map[String, String](("backlog", "10"))
              )
            )
          ),
          List[Connection](Connection("almaid", "korteid2"))
        )
      )
      val res = pipeManager ? config
      val result = Await.result(res, timeout.duration)
      result match {
        case Success(_) => fail("UploadConfig did not fail on lingering connection!");
        case Failure(e) => ;
      }

    }
  }

}

class TestConfigUploadAndDownloadCase extends TestKit(ActorSystem("HyperionTest2")) with ImplicitSender
  with WordSpecLike with MustMatchers with BeforeAndAfterAll {

  override def afterAll {
    Registry.reset
    shutdown()
  }

  "ConfigUploadDownload" must {
    implicit val timeout = Timeout(1000 millis)

    "be able to accept UploadConfig messages and then download config" in {

      val pipeManager = system.actorOf(Props(new PipeCreator(system, new PipeFactory("HyperionTest2"))), "creator4")
      val config = Config(
        List[NodeProperty](
          NodeProperty("almaid", 0, 0,
            PipeOptions("alma", "source",
              Map[String, String](("port", "10000"))
            )
          ),
          NodeProperty("korteid", 0, 0,
            PipeOptions("korte", "tail",
              Map[String, String](("backlog", "10"))
            )
          )
        ),
        List[Connection](Connection("almaid", "korteid"))
      )
      val uploadConfig = UploadConfig(config)
      val res = pipeManager ? uploadConfig
      val downloadedConfig = Await.result(pipeManager ? QueryConfig, timeout.duration).asInstanceOf[Config]
      assert(config == downloadedConfig)
    }
  }

}
