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

class TestPipeCase (_system: ActorSystem) extends TestKit(_system) with ImplicitSender
with WordSpecLike with MustMatchers with BeforeAndAfterAll {
 
	def this() = this(ActorSystem("MySpec"))
	
	override def afterAll {
	   _system.shutdown()
	}
	
	"Rewrite" must {
	  "rewrite a message" in 
	  {
	     val probe1 = TestProbe()
	     val probe2 = TestProbe()
	     val actor = system.actorOf(Props( new Rewrite("MESSAGE","kakukk","almafa")))
	     val expected = Message.withMessage("almafa")
	     actor ! AddPipe(system.actorSelection(probe1.ref.path))
	     actor ! AddPipe(system.actorSelection(probe2.ref.path))
	     actor ! Message.withMessage("kakukk")
	     probe1.expectMsg(100 millis, expected)
	     probe2.expectMsg(100 millis, expected)
	     
	  }
	  
	  "not change message if value is not present" in
	  {
	     val probe1 = TestProbe()
	     val actor = system.actorOf(Props( new Rewrite("AAA","kakukk","almafa")))
	     actor ! AddPipe(system.actorSelection(probe1.ref.path))
	     actor ! Message.withMessage("kakukk")
	     probe1.expectMsg(100 millis, Message.withMessage("kakukk"))
	  }
	}
	"MessageCounter" must {
	  implicit val timeout = Timeout(100 millis)
	  
	  "respond with zero in initial state" in {
	     val actor = system.actorOf(Props( new MessageCounter))
	     val future = actor ? Query
	     val result = Await.result(future, timeout.duration).asInstanceOf[Integer]
	     assert(result == 0)
	   }
	  
	  "respond with one if a message has arrived" in {
	     val actor = system.actorOf(Props( new MessageCounter))
	     actor ! Message.withMessage("kakukk")
	     val future = actor ? Query
	     val result = Await.result(future, timeout.duration).asInstanceOf[Integer]
	     assert(result == 1)
	  }
	  
	  "reset counter if Reset message got" in {
	     val actor = system.actorOf(Props( new MessageCounter))
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
	     val actor = system.actorOf(Props( new FieldValueCounter("AAA","kakukk")))
	     actor ! Message.withMessage("kakukk")
	     val future = actor ? Query
	     val result = Await.result(future, timeout.duration).asInstanceOf[Integer]
	     assert(result == 0)
	   }
	   
	   "count field is matches" in {
	     val actor = system.actorOf(Props( new FieldValueCounter("MESSAGE","kakukk")))
	     actor ! Message.withMessage("kakukk")
	     val future = actor ? Query
	     val result = Await.result(future, timeout.duration).asInstanceOf[Integer]
	     assert(result == 1)
	   }
	   
	   "not count field is not matches, but present" in {
	     val actor = system.actorOf(Props( new FieldValueCounter("MESSAGE","kakukk")))
	     actor ! Message.withMessage("almafa")
	     val future = actor ? Query
	     val result = Await.result(future, timeout.duration).asInstanceOf[Integer]
	     assert(result == 0)
	   }
	}
	
	"AverageCounter" must {
	  implicit val timeout = Timeout(1000 millis)
	  "return zero when no message present" in {
	    val counter = system.actorSelection(system.actorOf(Props( new MessageCounter)).path)
	    val averageCounter = system.actorOf(Props( new AverageCounter(counter, 100 millis, 10)))
	    val result = Await.result(averageCounter ? Query, timeout.duration).asInstanceOf[Integer]
	    assert(result == 0)
	    averageCounter ! PoisonPill
	    counter ! PoisonPill
	  }
	  
	  "after one message and one tick, average should one" in {
      val counter = system.actorSelection(system.actorOf(Props( new MessageCounter)).path)
	    val averageCounter = system.actorOf(Props( new AverageCounter(counter, 10000 millis, 10)))
	    counter ! Message.empty
	    averageCounter ! Tick
	    val result = Await.result(averageCounter ? Query, timeout.duration).asInstanceOf[Integer]
	    assert(result == 1)
	    averageCounter ! PoisonPill
	    counter ! PoisonPill
	  }
	  
	  "after one message per tick, average should one" in {
      val counter = system.actorSelection(system.actorOf(Props( new MessageCounter)).path)
	    val averageCounter = system.actorOf(Props( new AverageCounter(counter, 10000 millis, 10)))
	    counter ! Message.empty
	    averageCounter ! Tick
	    counter ! Message.empty
	    averageCounter ! Tick
	    val result = Await.result(averageCounter ? Query, timeout.duration).asInstanceOf[Integer]
	    assert(result == 1)
	    averageCounter ! PoisonPill
	    counter ! PoisonPill
	  }
	  
	  "should only keep backlogsizenumber of items" in {
      val counter = system.actorSelection(system.actorOf(Props( new MessageCounter)).path)
	    val averageCounter = system.actorOf(Props( new AverageCounter(counter, 10000 millis, 2)))
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
	    val tail = system.actorOf(Props( new Tail(1)))
	    val result = Await.result(tail ? Query, timeout.duration).asInstanceOf[List[Message]]
	    assert(result == List[Message]())
	  }

	  "return one element if one message arrived" in {
	    val tail = system.actorOf(Props( new Tail(1)))
	    tail ! Message.empty
	    val result = Await.result(tail ? Query, timeout.duration).asInstanceOf[List[Message]]
	    assert(result == List[Message](Message.empty))
	  }

	  "return at most backlogsize elements" in {
	    val tail = system.actorOf(Props( new Tail(1)))
	    tail ! Message.empty
	    tail ! Message.empty
	    val result = Await.result(tail ? Query, timeout.duration).asInstanceOf[List[Message]]
	    assert(result == List[Message](Message.empty))
	  }
	}

    "FieldStats" must {
      implicit val timeout = Timeout(1000 millis)
      "return empty map when no message arrived" in {
        val stats = system.actorOf(Props(new FieldStatistics("test")))
        val result = Await.result(stats ? Query, timeout.duration).asInstanceOf[Map[String,Integer]]
        assert(result == Map[String,Integer]())
      }

      "return empty map when non-matching message arrived" in
      {
        val stats = system.actorOf(Props(new FieldStatistics("test")))
        stats ! Message.withMessage("almafa")
        val result = Await.result(stats ? Query, timeout.duration).asInstanceOf[Map[String,Integer]]
        assert(result == Map[String,Integer]())
      }
     "return filled map when matching message arrived" in
      {
        val stats = system.actorOf(Props(new FieldStatistics("MESSAGE")))
        stats ! Message.withMessage("almafa")
        val result = Await.result(stats ? Query, timeout.duration).asInstanceOf[Map[String,Integer]]
        assert(result == Map[String,Integer]( "almafa" -> 1))

      }
     "return filled map when two matching messages arrived" in
      {
        val stats = system.actorOf(Props(new FieldStatistics("MESSAGE")))
        stats ! Message.withMessage("almafa")
        stats ! Message.withMessage("almafa")
        val result = Await.result(stats ? Query, timeout.duration).asInstanceOf[Map[String,Integer]]
        assert(result == Map[String,Integer]( "almafa" -> 2))

      }

    }
	
}
