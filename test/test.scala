import org.scalatest._
import hyperion._
import akka.actor._
import akka.testkit._
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import java.io._
import java.net._


import scala.concurrent.duration._
import scala.util._
import scala.concurrent.Await

class TestPipeCase(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with MustMatchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("HyperionTest2",  createTestconfig()))

  override def afterAll {
    _system.terminate()
  }

  "Rewrite" must {
    implicit val timeout = Timeout(100 millis)
    "rewrite a message" in {
      val probe1 = TestProbe()
      val probe2 = TestProbe()
      val actor = system.actorOf(Props(new Rewrite("id", "MESSAGE", "kakukk", "almafa")))
      val expected = Message.withMessage("almafa")
      actor ! PipeConnectionUpdate(Map(("alma", system.actorSelection(probe1.ref.path))),List())
      actor ! PipeConnectionUpdate(Map(("korte", system.actorSelection(probe2.ref.path))), List())
      actor ! Message.withMessage("kakukk")
      probe1.expectMsg(1000 millis, expected)
      probe2.expectMsg(1000 millis, expected)

    }

    "not change message if value is not present" in {
      val probe1 = TestProbe()
      val actor = system.actorOf(Props(new Rewrite("id", "AAA", "kakukk", "almafa")))
      actor ! PipeConnectionUpdate(Map(("barack", system.actorSelection(probe1.ref.path))),List())
      actor ! Message.withMessage("kakukk")
      probe1.expectMsg(1000 millis, Message.withMessage("kakukk"))
    }

    "be able to use template in value field" in {
      val probe1 = TestProbe()
      val actor = system.actorOf(Props(new Rewrite("id", "MESSAGE", "almafa", "${alma}")))
      actor ! PipeConnectionUpdate(Map(("barack", system.actorSelection(probe1.ref.path))),List())
      actor ! Message.withMessage("almafa").set("alma","korte")
      probe1.expectMsg(1000 millis, Message.withMessage("korte").set("alma","korte"))
    }

    "be able to report statistics" in {
      val probe1 = TestProbe()
      val actor = system.actorOf(Props(new Rewrite("id", "MESSAGE", "kakukk", "almafa")))
      actor ! PipeConnectionUpdate(Map(("barack", system.actorSelection(probe1.ref.path))),List())
      actor ! Message.withMessage("kakukk")
      probe1.expectMsg(1000 millis, Message.withMessage("almafa"))
      var statsResponse = Await.result(actor ? StatsRequest, timeout.duration)
      assert(statsResponse == StatsResponse(Map[String, Int]("processed" -> 1)))
    }
  }

  "Set" must {
    implicit val timeout = Timeout(100 millis)
    "set field with a literal value if field does not exists" in {
      val probe1 = TestProbe()
      val actor = system.actorOf(Props(new SetNode("id", "alma", "korte")))
      actor ! PipeConnectionUpdate(Map(("barack", system.actorSelection(probe1.ref.path))),List())
      actor ! Message.empty
      probe1.expectMsg(1000 millis, Message.empty.set("alma","korte"))
    }

   "set field with a template value if field does not exists" in {
      val probe1 = TestProbe()
      val actor = system.actorOf(Props(new SetNode("id", "alma", "${korte}")))
      actor ! PipeConnectionUpdate(Map(("barack", system.actorSelection(probe1.ref.path))),List())
      actor ! Message.empty.set("korte", "barack")
      probe1.expectMsg(1000 millis, Message.empty.set("alma","barack").set("korte", "barack"))
    }

  "overwrite field with a template value if already exists " in {
      val probe1 = TestProbe()
      val actor = system.actorOf(Props(new SetNode("id", "alma", "${korte}")))
      actor ! PipeConnectionUpdate(Map(("barack", system.actorSelection(probe1.ref.path))),List())
      actor ! Message.empty.set("korte", "barack").set("alma", "citrom")
      probe1.expectMsg(1000 millis, Message.empty.set("alma","barack").set("korte", "barack"))
    }

    "be able to report statistics" in {
      val probe1 = TestProbe()
      val actor = system.actorOf(Props(new SetNode("id", "alma", "korte")))
      actor ! PipeConnectionUpdate(Map(("barack", system.actorSelection(probe1.ref.path))),List())
      actor ! Message.empty
      probe1.expectMsg(1000 millis, Message.empty.set("alma","korte"))
      var statsResponse = Await.result(actor ? StatsRequest, timeout.duration)
      assert(statsResponse == StatsResponse(Map[String, Int]("processed" -> 1)))
    }

  }

  "Filter" must {
    implicit val timeout = Timeout(100 millis)
    "be able to propagate message if matches" in {
      val probe1 = TestProbe()
      val actor = system.actorOf(Props(new hyperion.Filter("id", "MESSAGE", "korte")))
      actor ! PipeConnectionUpdate(Map(("barack", system.actorSelection(probe1.ref.path))),List())
      actor ! Message.withMessage("korte")
      probe1.expectMsg(1000 millis, Message.withMessage("korte"))
      var statsResponse = Await.result(actor ? StatsRequest, timeout.duration)
      assert(statsResponse == StatsResponse(Map[String, Int]("processed" -> 1, "matched" ->1)))
    }

    "be able to drop message if does not match" in {
      val probe1 = TestProbe()
      val actor = system.actorOf(Props(new hyperion.Filter("id", "MESSAGE", "korte")))
      actor ! PipeConnectionUpdate(Map(("barack", system.actorSelection(probe1.ref.path))),List())
      actor ! Message.withMessage("alma")
      probe1.expectNoMsg(1000 millis)
      var statsResponse = Await.result(actor ? StatsRequest, timeout.duration)
      assert(statsResponse == StatsResponse(Map[String, Int]("processed" -> 1, "matched" ->0)))
    }
  }

  "MessageCounter" must {
    implicit val timeout = Timeout(1000 millis)

    "respond with zero in initial state" in {
      val actor = system.actorOf(Props(new MessageCounter("id")))
      val future = actor ? StatsRequest
      val result = Await.result(future, timeout.duration).asInstanceOf[StatsResponse].values
      assert(result("counter") == 0)
    }

    "respond with one if a message has arrived" in {
      val actor = system.actorOf(Props(new MessageCounter("id")))
      actor ! Message.withMessage("kakukk")
      val future = actor ? StatsRequest
      val result = Await.result(future, timeout.duration).asInstanceOf[StatsResponse].values
      assert(result("counter") == 1)
    }

    "reset counter if Reset message got" in {
      val actor = system.actorOf(Props(new MessageCounter("id")))
      actor ! Message.withMessage("kakukk")
      actor ! Reset
      val future = actor ? StatsRequest
      val result = Await.result(future, timeout.duration).asInstanceOf[StatsResponse].values
      assert(result("counter") == 0)
    }
  }

  "FieldValueCounter" must {
    implicit val timeout = Timeout(100 millis)
    "not count anything if field is not present" in {

      val actor = system.actorOf(Props(new FieldValueCounter("id","AAA", "kakukk")))
      actor ! Message.withMessage("kakukk")
      val future = actor ? StatsRequest
      val result = Await.result(future, timeout.duration).asInstanceOf[StatsResponse].values
      assert(result("counter")== 0)

    }

    "count field is matches" in {
      val actor = system.actorOf(Props(new FieldValueCounter("id", "MESSAGE", "kakukk")))
      actor ! Message.withMessage("kakukk")
      val future = actor ? StatsRequest
      val result = Await.result(future, timeout.duration).asInstanceOf[StatsResponse].values
      assert(result("counter") == 1)
    }

    "not count field is not matches, but present" in {
      val actor = system.actorOf(Props(new FieldValueCounter("id","MESSAGE", "kakukk")))
      actor ! Message.withMessage("almafa")
      val future = actor ? StatsRequest
      val result = Await.result(future, timeout.duration).asInstanceOf[StatsResponse].values
      assert(result("counter") == 0)
    }
  }

  "AverageCounter" must {
    implicit val timeout = Timeout(10000 millis)
    "return zero when no message present" in {
      val counter = system.actorSelection(system.actorOf(Props(new MessageCounter("id"))).path.toString)
      val averageCounter = system.actorOf(Props(new AverageCounter("id", counter, 100 millis, 10)))
      val result = Await.result(averageCounter ? StatsRequest, timeout.duration).asInstanceOf[StatsResponse].values
      assert(result("counter") == 0)
      averageCounter ! PoisonPill
      counter ! PoisonPill
    }

    "after one message and one tick, average should one" in {
      val counter = system.actorSelection(system.actorOf(Props(new MessageCounter("id"))).path.toString)
      val averageCounter = system.actorOf(Props(new AverageCounter("id", counter, 10000 millis, 10)))
      counter ! Message.empty
      averageCounter ! Tick
      val result = Await.result(averageCounter ? StatsRequest, timeout.duration).asInstanceOf[StatsResponse].values
      assert(result("counter") == 1)
      averageCounter ! PoisonPill
      counter ! PoisonPill
    }

    "after one message per tick, average should one" in {
      val counter = system.actorSelection(system.actorOf(Props(new MessageCounter("id"))).path.toString)
      val averageCounter = system.actorOf(Props(new AverageCounter("id", counter, 10000 millis, 10)))
      Thread.sleep(10)
      counter ! Message.empty
      Thread.sleep(10)
      averageCounter ! Tick
      Thread.sleep(10)
      counter ! Message.empty
      Thread.sleep(10)
      averageCounter ! Tick
      Thread.sleep(10)
      val result = Await.result(averageCounter ? StatsRequest, timeout.duration).asInstanceOf[StatsResponse].values
      assert(result("counter") == 1)
      averageCounter ! PoisonPill
      counter ! PoisonPill
    }

    "should only keep backlogsizenumber of items" in {
      val counter = system.actorSelection(system.actorOf(Props(new MessageCounter("id"))).path.toString)
      val averageCounter = system.actorOf(Props(new AverageCounter("id", counter, 10000 millis, 2)))
      counter ! Message.empty
      counter ! Message.empty
      counter ! Message.empty
      Thread.sleep(10)
      averageCounter ! Tick
      Thread.sleep(10)
      counter ! Message.empty
      Thread.sleep(10)
      averageCounter ! Tick
      Thread.sleep(10)
      counter ! Message.empty
      Thread.sleep(10)
      averageCounter ! Tick
      Thread.sleep(10)
      val result = Await.result(averageCounter ? StatsRequest, timeout.duration).asInstanceOf[StatsResponse].values
      assert(result("counter") == 1)
      averageCounter ! PoisonPill
      counter ! PoisonPill
    }
  }

  "Tail" must {
    implicit val timeout = Timeout(1000 millis)
    "return empty list when no message arrived" in {
      val tail = system.actorOf(Props(new Tail("id", 1)))
      val result = Await.result(tail ? Query, timeout.duration).asInstanceOf[List[Message]]
      assert(result == List[Message]())
    }

    "return one element if one message arrived" in {
      val tail = system.actorOf(Props(new Tail("id", 1)))
      tail ! Message.empty
      val result = Await.result(tail ? Query, timeout.duration).asInstanceOf[List[Message]]
      assert(result == List[Message](Message.empty))
    }

    "return at most backlogsize elements" in {
      val tail = system.actorOf(Props(new Tail("id", 1)))
      tail ! Message.empty
      tail ! Message.empty
      val result = Await.result(tail ? Query, timeout.duration).asInstanceOf[List[Message]]
      assert(result == List[Message](Message.empty))
    }
  }

  "FieldStats" must {
    implicit val timeout = Timeout(1000 millis)
    "return empty map when no message arrived" in {
      val stats = system.actorOf(Props(new FieldStatistics("id", "test")))
      val result = Await.result(stats ? StatsRequest, timeout.duration).asInstanceOf[StatsResponse].values
      assert(result == Map[String, Integer]())
    }

    "return empty map when non-matching message arrived" in {
      val stats = system.actorOf(Props(new FieldStatistics("id", "test")))
      stats ! Message.withMessage("almafa")
      val result = Await.result(stats ? StatsRequest, timeout.duration).asInstanceOf[StatsResponse].values
      assert(result == Map[String, Integer]())
    }
    "return filled map when matching message arrived" in {
      val stats = system.actorOf(Props(new FieldStatistics("id", "MESSAGE")))
      stats ! Message.withMessage("almafa")
      val result = Await.result(stats ? StatsRequest, timeout.duration).asInstanceOf[StatsResponse].values
      assert(result == Map[String, Integer]("almafa" -> 1))

    }
    "return filled map when two matching messages arrived" in {
      val stats = system.actorOf(Props(new FieldStatistics("id", "MESSAGE")))
      stats ! Message.withMessage("almafa")
      stats ! Message.withMessage("almafa")
      val result = Await.result(stats ? StatsRequest, timeout.duration).asInstanceOf[StatsResponse].values
      assert(result == Map[String, Integer]("almafa" -> 2))

    }

  }
   
  "TcpSource" must {
    implicit val timeout = Timeout(1000 millis)
    "be able to work" in {
      val actor = system.actorOf(Props(new TcpSource("source", 11112, "syslog")), "id")
      val probe1 = TestProbe()
      actor ! PipeConnectionUpdate(Map(("id", system.actorSelection(probe1.ref.path.toString))),List())
      Thread.sleep(200)
      val skt = new Socket("localhost", 11112);
      val out = new PrintWriter(skt.getOutputStream());
      out.print("<38>2013-11-11T01:01:31 localhost prg00000[1234]: seq: 0000009579, thread: 0000, runid: 1384128081, stamp: 2013-11-11T01:01:31 PADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADD\n")
      out.close();
      skt.close();
      val expected = Message(Map("PROGRAM" -> "prg00000", "HOST" -> "localhost", "MESSAGE" -> "seq: 0000009579, thread: 0000, runid: 1384128081, stamp: 2013-11-11T01:01:31 PADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADD", "PID" -> "[1234]", "PRIO" -> "38", "DATE" -> "1384131691000"))
      probe1.expectMsg(1000 millis, expected)
      var statsResponse = Await.result(actor ? StatsRequest, timeout.duration)
      assert(statsResponse == StatsResponse(Map[String, Int]("processed" -> 1)))
      actor ! PipeShutdown(List())
      Thread.sleep(100)
     }

    "be able resume when parse fails" in {
      val actor = system.actorOf(Props(new TcpSource("source2", 11213, "json")), "id2")
      val probe1 = TestProbe()
      actor ! PipeConnectionUpdate(Map(("id", system.actorSelection(probe1.ref.path.toString))),List())
      Thread.sleep(200)
      val skt = new Socket("localhost", 11213);
      val out = new PrintWriter(skt.getOutputStream());
      out.print("blabla\n")
      out.print("{\"alma\":\"korte\"}\n")
      out.close()
      skt.close()
      val expected = Message(Map("alma" -> "korte"))
      probe1.expectMsgPF(1000 millis) {
        case x : Message => x("alma") == "korte"
      }
      actor ! PipeShutdown(List())
      Thread.sleep(100)
    }

    "be able resume when parse fails in individual read cycles" in {
      val actor = system.actorOf(Props(new TcpSource("source2", 11113, "json")), "id2")
      val probe1 = TestProbe()
      actor ! PipeConnectionUpdate(Map(("id", system.actorSelection(probe1.ref.path.toString))),List())
      Thread.sleep(200)
      val skt = new Socket("localhost", 11113);
      val out = new PrintWriter(skt.getOutputStream());
      out.print("blabla\n")
      out.flush()
      Thread.sleep(100)
      out.print("{\"alma\":\"korte\"}\n")
      out.close()
      skt.close()
      val expected = Message(Map("alma" -> "korte"))
      probe1.expectMsgPF(1000 millis) {
        case x : Message => x("alma") == "korte"
      }
      actor ! PipeShutdown(List())
      Thread.sleep(100)
    }
  }

  "TcpDestination" must {
    implicit val timeout = Timeout(1000 millis)
    "be able to send message to TcpSource" in {
      val server = system.actorOf(Props(new TcpSource("source3", 11114, "raw")), "sourc1")
      val client = system.actorOf(Props(new TcpDestination("destination", "localhost", 11114, "$MESSAGE\n")), "dest1")
      val probe1 = TestProbe()
      Thread.sleep(100)
      server ! PipeConnectionUpdate(Map(("id", system.actorSelection(probe1.ref.path.toString))),List())
      Thread.sleep(500)
      val expected = Message.withMessage("alma")
      client ! expected
      probe1.expectMsg(1000 millis, expected)
      server ! PipeShutdown(List())
      client ! PipeShutdown(List())
      Thread.sleep(100)
    }
 
    "be able to keep message when server is not available" in {
      val client = system.actorOf(Props(new TcpDestination("destination2", "localhost", 11115, "$MESSAGE\n")), "dest2")
      Thread.sleep(100)
      Thread.sleep(500)
      val expected = Message.withMessage("alma")
      client ! expected
      Thread.sleep(500)
      var statsResponse = Await.result(client ? StatsRequest, timeout.duration)
      assert(statsResponse == StatsResponse(Map[String, Int]("processed" -> 1, "queued" -> 1)))
      val server = system.actorOf(Props(new TcpSource("source4", 11115, "raw")), "sourc2")
      val probe1 = TestProbe()
      server ! PipeConnectionUpdate(Map(("id", system.actorSelection(probe1.ref.path.toString))),List())
      probe1.expectMsg(10000 millis, expected)
      statsResponse = Await.result(client ? StatsRequest, timeout.duration)
      assert(statsResponse == StatsResponse(Map[String, Int]("processed" -> 1, "queued" -> 0)))
      server ! PipeShutdown(List())
      client ! PipeShutdown(List())
    }

    "be able to reconnect when server is not available" in {
      // Set up server & client
      val server = system.actorOf(Props(new TcpSource("source5", 11116, "raw")), "source3")
      val client = system.actorOf(Props(new TcpDestination("destination3", "localhost", 11116, "$MESSAGE\n")), "dest3")
      val probe1 = TestProbe()
      server ! PipeConnectionUpdate(Map(("id", system.actorSelection(probe1.ref.path.toString))),List())
      Thread.sleep(100)
      // Send message to client
      val expected = Message.withMessage("alma")
      client ! expected
      Thread.sleep(100)
      probe1.expectMsg(1000 millis, expected)
      var statsResponse = Await.result(client ? StatsRequest, timeout.duration)
      assert(statsResponse == StatsResponse(Map[String, Int]("processed" -> 1, "queued" -> 0)))
      // Shut down server
      server ! PipeShutdown(List())
      Thread.sleep(100)
      // Send second message to client
      val expected2 = Message.withMessage("alma2")
      client ! expected2
      Thread.sleep(100)
      statsResponse = Await.result(client ? StatsRequest, timeout.duration)
      assert(statsResponse == StatsResponse(Map[String, Int]("processed" -> 2, "queued" -> 1)))
      // Recreate server again, attach same probe
      val server2 = system.actorOf(Props(new TcpSource("source6", 11116, "raw")), "source3_1")
      server2 ! PipeConnectionUpdate(Map(("id", system.actorSelection(probe1.ref.path.toString))),List())
      Thread.sleep(100)
      // Send third message to client
      val expected3 = Message.withMessage("alma3")
      client ! expected3
      Thread.sleep(100)
      // Assert for two messages
      probe1.expectMsg(10000 millis, expected2)
      probe1.expectMsg(10000 millis, expected3)
      statsResponse = Await.result(client ? StatsRequest, timeout.duration)
      assert(statsResponse == StatsResponse(Map[String, Int]("processed" -> 3, "queued" -> 0)))
      client ! PipeShutdown(List())
      server2 ! PipeShutdown(List())
    }
  }

  "ParserNode" must {
    "be able to parse a field" in {
      val actor = system.actorOf(Props(new ParserNode("parserid", "MESSAGE", "syslog", "")), "parserid")
      val msg = Message.withMessage("<38>2013-11-11T01:01:31 localhost prg00000[1234]: msgpart")
      val probe1 = TestProbe()
      actor ! PipeConnectionUpdate(Map(("parserid", system.actorSelection(probe1.ref.path.toString))),List())
      Thread.sleep(100)
      actor ! msg
      val expected = Message(Map("PROGRAM" -> "prg00000", "HOST" -> "localhost", "MESSAGE" -> "msgpart", "PID" -> "[1234]", "PRIO" -> "38", "DATE" -> "1384131691000"))
      probe1.expectMsg(1000 millis, expected)
      actor ! PipeShutdown(List())
    }
  }
}

class TestShutDown(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with MustMatchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("HyperionTest3", createTestconfig()))

  override def afterAll {
    _system.terminate()
  }

  "TestShutDown" must {
    implicit val timeout = Timeout(1000 millis)
    "wait until all shutdown message arrived" in {
      val tail = system.actorOf(Props(new Tail("id", 10)))
      tail ! Message.empty
      val result = Await.result(tail ? Query, timeout.duration).asInstanceOf[List[Message]]
      assert(result == List[Message](Message.empty))
      tail ! PipeShutdown(List("alma"))
      val result2 = Await.result(tail ? Query, timeout.duration).asInstanceOf[List[Message]]
      assert(result2 == List[Message](Message.empty))
      tail ! Disconnected("alma")
      try {
        val result3 = Await.result(tail ? Query, timeout.duration).asInstanceOf[List[Message]]
        fail()
      } catch {
        case _ : java.util.concurrent.TimeoutException =>
      }

    }
  }
}
