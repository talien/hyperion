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

class TestPipeCreatorCase extends TestKit(ActorSystem("HyperionTest1", createTestconfig())) with ImplicitSender
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
      Thread.sleep(100)
      system.actorSelection("akka://HyperionTest1/user/pipe_almafa") ! Message.empty
      Thread.sleep(100)
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
            NodeProperty("almaid2", 0, 0,
              PipeOptions("alma", "source",
                Map[String, String](("port", "10000"))
              )
            ),
            NodeProperty("korteid2", 0, 0,
              PipeOptions("korte", "tail",
                Map[String, String](("backlog", "10"))
              )
            )
          ),
          List[Connection](Connection("almaid2", "korteid3"))
        )
      )
      val res = pipeManager ? config
      val result = Await.result(res, timeout.duration)
      result match {
        case Success(_) => fail("UploadConfig did not fail on lingering connection!");
        case Failure(e) => ;
      }

    }
    
    "be able to set up TcpSource and TcpDestination properly" in {
      val pipeManager = system.actorOf(Props(new PipeCreator(system, new PipeFactory("HyperionTest1"))), "creator5")
      val probe1 = TestProbe()
      val config = UploadConfig(
        Config(
          List[NodeProperty](
            NodeProperty("almaid4", 0, 0,
              PipeOptions("alma", "source",
                Map[String, String](("port", "11115"),("parser","raw"))
              )
            ),
            NodeProperty("korteid4", 0, 0,
              PipeOptions("korte", "destination",
                Map[String, String](("host","localhost"),("port", "11115"),("template","$MESSAGE\n"))
              )
            )
          ),
          List[Connection]()
        )
      )
      pipeManager ? config
      Thread.sleep(100)
      system.actorSelection("akka://HyperionTest1/user/pipe_almaid4") ! PipeConnectionUpdate(Map(("id", system.actorSelection(probe1.ref.path.toString))),List())
      Thread.sleep(500)
      val expected = Message.withMessage("testMessage")
      system.actorSelection("akka://HyperionTest1/user/pipe_korteid4") ! expected
      probe1.expectMsg(1000 millis, expected)

    }
  }
  implicit val timeout = Timeout(10000 millis)
  "be able to accept UploadConfig message and set up pipe system" in {
    Registry.reset
    val pipeManager = system.actorOf(Props(new PipeCreator(system, new PipeFactory("HyperionTest1"))), "creator4")
    val config = UploadConfig(
      Config(
        List[NodeProperty](
          NodeProperty("almaid3", 0, 0,
            PipeOptions("alma", "filter",
              Map[String, String](("fieldname", "MESSAGE"), ("matchexpr","alma"))
            )
          ),
          NodeProperty("korteid3", 0, 0,
            PipeOptions("korte", "tail",
              Map[String, String](("backlog", "10"))
            )
          )
        ),
        List[Connection](Connection("almaid3", "korteid3"))
      )
    )
    val res = pipeManager ? config
    Thread.sleep(100)
    system.actorSelection("akka://HyperionTest1/user/pipe_almaid3") ! Message.withMessage("alma")
    Thread.sleep(100)
    val result = Await.result(pipeManager ? TailQuery("korteid3"), timeout.duration).asInstanceOf[List[Message]]
    assert(result == List[Message](Message.withMessage("alma")))
  }

  "be able to query all stats in pipe system" in {
    Registry.reset
    val pipeManager = system.actorOf(Props(new PipeCreator(system, new PipeFactory("HyperionTest1"))), "creator6")
    val config = UploadConfig(
      Config(
        List[NodeProperty](
          NodeProperty("almaid5", 0, 0,
            PipeOptions("alma", "filter",
              Map[String, String](("fieldname", "MESSAGE"), ("matchexpr","alma"))
            )
          ),
          NodeProperty("korteid5", 0, 0,
            PipeOptions("korte", "counter",
              Map[String, String]()
            )
          )
        ),
        List[Connection](Connection("almaid5", "korteid5"))
      )
    )
    val res = pipeManager ? config
    Thread.sleep(100)
    system.actorSelection("akka://HyperionTest1/user/pipe_almaid5") ! Message.withMessage("alma")
    Thread.sleep(100)
    val result = Await.result(pipeManager ? AllStatsQuery, timeout.duration).asInstanceOf[Map[String, Map[String, Int]]]
    val expectedResult = Map[String, Map[String, Int]](
      "almaid5" -> Map[String, Int]( "processed" -> 1, "matched" -> 1),
      "korteid5" ->  Map[String, Int] ("counter" -> 1))
    assert(result == expectedResult)
  }

}

class TestConfigUploadAndDownloadCase extends TestKit(ActorSystem("HyperionTest2", createTestconfig())) with ImplicitSender
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

class TestRemoveConnection extends TestKit(ActorSystem("HyperionTest3", createTestconfig())) with ImplicitSender
  with WordSpecLike with MustMatchers with BeforeAndAfterAll {

  override def afterAll {
    Registry.reset
    shutdown()
  }

  "RemoveConnection" must {
    implicit val timeout = Timeout(1000 millis)

    "be able to remove connection if it does not present in second config" in {

      val pipeManager = system.actorOf(Props(new PipeCreator(system, new PipeFactory("HyperionTest3"))), "creator1")
      val config = Config(
        List[NodeProperty](
          NodeProperty("almaid", 0, 0,
            PipeOptions("alma", "filter",
              Map[String, String](("fieldname", "MESSAGE"), ("matchexpr","alma"))
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
      Thread.sleep(100)
      system.actorSelection("akka://HyperionTest3/user/pipe_almaid") ! Message.withMessage("alma")
      Thread.sleep(100)
      val result = Await.result(pipeManager ? TailQuery("korteid"), timeout.duration).asInstanceOf[List[Message]]
      assert(result == List[Message](Message.withMessage("alma")))
      val configWithoutConnection = Config(
        List[NodeProperty](
          NodeProperty("almaid", 0, 0,
            PipeOptions("alma", "filter",
              Map[String, String](("fieldname", "MESSAGE"), ("matchexpr","alma"))
            )
          ),
          NodeProperty("korteid", 0, 0,
            PipeOptions("korte", "tail",
              Map[String, String](("backlog", "10"))
            )
          )
        ),
        List[Connection]()
      )
      val uploadConfig2 = UploadConfig(configWithoutConnection)
      pipeManager ? uploadConfig2
      Thread.sleep(100)
      system.actorSelection("akka://HyperionTest3/user/pipe_almaid") ! Message.withMessage("alma")
      Thread.sleep(100)
      val result2 = Await.result(pipeManager ? TailQuery("korteid"), timeout.duration).asInstanceOf[List[Message]]
      assert(result2 == List[Message](Message.withMessage("alma")))
    }
  }

}

class TestRemovePipe extends TestKit(ActorSystem("HyperionTest4", createTestconfig())) with ImplicitSender
  with WordSpecLike with MustMatchers with BeforeAndAfterAll {

  override def afterAll {
    Registry.reset
    shutdown()
  }

  "RemovePipe" must {
    implicit val timeout = Timeout(1000 millis)

    "be able to remove pipe if it does not present in second config" in {

      val pipeManager = system.actorOf(Props(new PipeCreator(system, new PipeFactory("HyperionTest4"))), "creator1")
      val config = Config(
        List[NodeProperty](
          NodeProperty("almaid", 0, 0,
            PipeOptions("alma", "filter",
              Map[String, String](("fieldname", "MESSAGE"), ("matchexpr","alma"))
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
      Thread.sleep(100)
      system.actorSelection("akka://HyperionTest4/user/pipe_almaid") ! Message.withMessage("alma")
      Thread.sleep(100)
      val result = Await.result(pipeManager ? TailQuery("korteid"), timeout.duration).asInstanceOf[List[Message]]
      assert(result == List[Message](Message.withMessage("alma")))
      val configWithoutConnection = Config(
        List[NodeProperty](
          NodeProperty("almaid", 0, 0,
            PipeOptions("alma", "filter",
              Map[String, String](("fieldname", "MESSAGE"), ("matchexpr","alma"))
            )
          )
        ),
        List[Connection]()
      )
      val uploadConfig2 = UploadConfig(configWithoutConnection)
      pipeManager ? uploadConfig2
      Thread.sleep(100)
      system.actorSelection("akka://HyperionTest4/user/pipe_almaid") ! Message.withMessage("alma")
      Thread.sleep(100)
      try{
        Await.result(pipeManager ? TailQuery("korteid"), timeout.duration).asInstanceOf[List[Message]]
        fail()
      }
      catch {
        case _ : akka.actor.ActorNotFound =>
      }
      val downloadedConfig = Await.result(pipeManager ? QueryConfig, timeout.duration).asInstanceOf[Config]
      assert(configWithoutConnection == downloadedConfig)
    }
  }

}
