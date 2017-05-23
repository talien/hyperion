import org.scalatest._
import hyperion._
import akka.actor._
import akka.testkit._
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import java.io._
import java.net._
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.util._
import scala.concurrent.Await

class TestPipeCase(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with MustMatchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("HyperionTest2", ConfigFactory.load("application.conf") ))

  override def afterAll {
    _system.shutdown()
  }

  "Rewrite" must {
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
  }
  "MessageCounter" must {
    implicit val timeout = Timeout(1000 millis)

    "respond with zero in initial state" in {
      val actor = system.actorOf(Props(new MessageCounter("id")))
      val future = actor ? Query
      val result = Await.result(future, timeout.duration).asInstanceOf[Integer]
      assert(result == 0)
    }

    "respond with one if a message has arrived" in {
      val actor = system.actorOf(Props(new MessageCounter("id")))
      actor ! Message.withMessage("kakukk")
      val future = actor ? Query
      val result = Await.result(future, timeout.duration).asInstanceOf[Integer]
      assert(result == 1)
    }

    "reset counter if Reset message got" in {
      val actor = system.actorOf(Props(new MessageCounter("id")))
      actor ! Message.withMessage("kakukk")
      actor ! Reset
      val future = actor ? Query
      val result = Await.result(future, timeout.duration).asInstanceOf[Integer]
      assert(result == 0)
    }
  }

  "FieldValueCounter" must {
    implicit val timeout = Timeout(100 millis)
    "not count anything if field is not present" in {
      val actor = system.actorOf(Props(new FieldValueCounter("id","AAA", "kakukk")))
      actor ! Message.withMessage("kakukk")
      val future = actor ? Query
      val result = Await.result(future, timeout.duration).asInstanceOf[Integer]
      assert(result == 0)
    }

    "count field is matches" in {
      val actor = system.actorOf(Props(new FieldValueCounter("id", "MESSAGE", "kakukk")))
      actor ! Message.withMessage("kakukk")
      val future = actor ? Query
      val result = Await.result(future, timeout.duration).asInstanceOf[Integer]
      assert(result == 1)
    }

    "not count field is not matches, but present" in {
      val actor = system.actorOf(Props(new FieldValueCounter("id","MESSAGE", "kakukk")))
      actor ! Message.withMessage("almafa")
      val future = actor ? Query
      val result = Await.result(future, timeout.duration).asInstanceOf[Integer]
      assert(result == 0)
    }
  }

  "AverageCounter" must {
    implicit val timeout = Timeout(10000 millis)
    "return zero when no message present" in {
      val counter = system.actorSelection(system.actorOf(Props(new MessageCounter("id"))).path.toString)
      val averageCounter = system.actorOf(Props(new AverageCounter("id", counter, 100 millis, 10)))
      val result = Await.result(averageCounter ? Query, timeout.duration).asInstanceOf[Integer]
      assert(result == 0)
      averageCounter ! PoisonPill
      counter ! PoisonPill
    }

    "after one message and one tick, average should one" in {
      val counter = system.actorSelection(system.actorOf(Props(new MessageCounter("id"))).path.toString)
      val averageCounter = system.actorOf(Props(new AverageCounter("id", counter, 10000 millis, 10)))
      counter ! Message.empty
      averageCounter ! Tick
      val result = Await.result(averageCounter ? Query, timeout.duration).asInstanceOf[Integer]
      assert(result == 1)
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
      val result = Await.result(averageCounter ? Query, timeout.duration).asInstanceOf[Integer]
      assert(result == 1)
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
      val result = Await.result(averageCounter ? Query, timeout.duration).asInstanceOf[Integer]
      assert(result == 1)
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
      val result = Await.result(stats ? Query, timeout.duration).asInstanceOf[Map[String, Integer]]
      assert(result == Map[String, Integer]())
    }

    "return empty map when non-matching message arrived" in {
      val stats = system.actorOf(Props(new FieldStatistics("id", "test")))
      stats ! Message.withMessage("almafa")
      val result = Await.result(stats ? Query, timeout.duration).asInstanceOf[Map[String, Integer]]
      assert(result == Map[String, Integer]())
    }
    "return filled map when matching message arrived" in {
      val stats = system.actorOf(Props(new FieldStatistics("id", "MESSAGE")))
      stats ! Message.withMessage("almafa")
      val result = Await.result(stats ? Query, timeout.duration).asInstanceOf[Map[String, Integer]]
      assert(result == Map[String, Integer]("almafa" -> 1))

    }
    "return filled map when two matching messages arrived" in {
      val stats = system.actorOf(Props(new FieldStatistics("id", "MESSAGE")))
      stats ! Message.withMessage("almafa")
      stats ! Message.withMessage("almafa")
      val result = Await.result(stats ? Query, timeout.duration).asInstanceOf[Map[String, Integer]]
      assert(result == Map[String, Integer]("almafa" -> 2))

    }

  }
   
  "TcpSource" must {
    "be able to work" in {
      val actor = system.actorOf(Props(new TcpSource("source", 11111, "syslog")), "id")
      val probe1 = TestProbe()
      actor ! PipeConnectionUpdate(Map(("id", system.actorSelection(probe1.ref.path.toString))),List())
      Thread.sleep(100)
      val skt = new Socket("localhost", 11111);
      val out = new PrintWriter(skt.getOutputStream());
      out.print("<38>2013-11-11T01:01:31 localhost prg00000[1234]: seq: 0000009579, thread: 0000, runid: 1384128081, stamp: 2013-11-11T01:01:31 PADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADD\n")
      out.close();
      skt.close();
      val expected = Message(Map("PROGRAM" -> "prg00000", "HOST" -> "localhost", "MESSAGE" -> "seq: 0000009579, thread: 0000, runid: 1384128081, stamp: 2013-11-11T01:01:31 PADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADD", "PID" -> "[1234]", "PRIO" -> "38", "DATE" -> "1384128091000"))
      probe1.expectMsg(1000 millis, expected)
      actor ! PipeShutdown(List())
     }
  }

  "TcpDestination" must {
    "be able to send message to TcpSource" in {
      val server = system.actorOf(Props(new TcpSource("source", 11111, "raw")), "sourc1")
      val client = system.actorOf(Props(new TcpDestination("destination", "localhost", 11111, "$MESSAGE\n")), "dest1")
      val probe1 = TestProbe()
      Thread.sleep(100)
      server ! PipeConnectionUpdate(Map(("id", system.actorSelection(probe1.ref.path.toString))),List())
      Thread.sleep(500)
      val expected = Message.withMessage("alma")
      client ! expected
      probe1.expectMsg(1000 millis, expected)
      server ! PipeShutdown(List())
      client ! PipeShutdown(List())
    }
 
    "be able to keep message when server is not available" in {
      val client = system.actorOf(Props(new TcpDestination("destination", "localhost", 11111, "$MESSAGE\n")), "dest2")
      Thread.sleep(100)
      Thread.sleep(500)
      val expected = Message.withMessage("alma")
      client ! expected
      Thread.sleep(500)
      val server = system.actorOf(Props(new TcpSource("source", 11111, "raw")), "sourc2")
      val probe1 = TestProbe()
      server ! PipeConnectionUpdate(Map(("id", system.actorSelection(probe1.ref.path.toString))),List())
      probe1.expectMsg(10000 millis, expected)
      server ! PipeShutdown(List())
      client ! PipeShutdown(List())
  
    }
  }

  "ParserNode" must {
    "be able to parse a field" in {
      val actor = system.actorOf(Props(new ParserNode("id", "MESSAGE", "syslog", "")), "id")
      val msg = Message.withMessage("<38>2013-11-11T01:01:31 localhost prg00000[1234]: msgpart")
      val probe1 = TestProbe()
      actor ! PipeConnectionUpdate(Map(("id", system.actorSelection(probe1.ref.path.toString))),List())
      Thread.sleep(100)
      actor ! msg
      val expected = Message(Map("PROGRAM" -> "prg00000", "HOST" -> "localhost", "MESSAGE" -> "msgpart", "PID" -> "[1234]", "PRIO" -> "38", "DATE" -> "1384128091000"))
      probe1.expectMsg(1000 millis, expected)
      actor ! PipeShutdown(List())
    }
  }
}

class TestShutDown(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with MustMatchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("HyperionTest3"))

  override def afterAll {
    _system.shutdown()
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
