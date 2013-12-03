import org.scalatest._
import hyperion._
import akka.actor._
import akka.testkit._
import akka.pattern.{ ask, pipe }
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.util._
import scala.concurrent.Await

class TestMessage extends FlatSpec {
    it should "be able to parse a single line" in {
        val line = "<38>2013-11-11T01:01:31 localhost prg00000[1234]: seq: 0000009579, thread: 0000, runid: 1384128081, stamp: 2013-11-11T01:01:31 PADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADD"
        val result = parseMessage(line)
        assert(result("PRIO") == "38")
        assert(result("HOST") == "localhost")
        assert(result("PROGRAM") == "prg00000")
        assert(result("PID") == "[1234]")
    }
    
    it should "be able to parse with legacy time" in {
    	val line = "<30>Nov 30 18:28:01 ubu1 avahi-daemon[26471]: Successfully called chroot()."
    	val result = parseMessage(line)
    	assert(result("PRIO") == "30")
    	assert(result("HOST") == "ubu1")
    	assert(result("PROGRAM") == "avahi-daemon")
    	assert(result("PID") == "[26471]")
    	assert(result("MESSAGE") == "Successfully called chroot().")
    }
    
    it should "be able to parse with legacy time and one-digit day" in {
    	val line = "<30>Dec  2 18:28:01 ubu1 avahi-daemon[26471]: Successfully called chroot()."
    	val result = parseMessage(line)
    	assert(result("PRIO") == "30")
    	assert(result("HOST") == "ubu1")
    	assert(result("PROGRAM") == "avahi-daemon")
    	assert(result("PID") == "[26471]")
    	assert(result("MESSAGE") == "Successfully called chroot().")
    }
}

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
	     actor ! AddActor(probe1.ref)
	     actor ! AddActor(probe2.ref)
	     actor ! Message.withMessage("kakukk")
	     probe1.expectMsg(100 millis, expected)
	     probe2.expectMsg(100 millis, expected)
	     
	  }
	  
	  "not change message if value is not present" in
	  {
	     val probe1 = TestProbe()
	     val actor = system.actorOf(Props( new Rewrite("AAA","kakukk","almafa")))
	     actor ! AddActor(probe1.ref)
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
	    val counter = system.actorOf(Props( new MessageCounter))
	    val averageCounter = system.actorOf(Props( new AverageCounter(counter, 100 millis, 10)))
	    val result = Await.result(averageCounter ? Query, timeout.duration).asInstanceOf[Integer]
	    assert(result == 0)
	    averageCounter ! PoisonPill
	    counter ! PoisonPill
	  }
	  
	  "after one message and one tick, average should one" in {
	    val counter = system.actorOf(Props( new MessageCounter))
	    val averageCounter = system.actorOf(Props( new AverageCounter(counter, 10000 millis, 10)))
	    counter ! Message.empty
	    averageCounter ! Tick
	    val result = Await.result(averageCounter ? Query, timeout.duration).asInstanceOf[Integer]
	    assert(result == 1)
	    averageCounter ! PoisonPill
	    counter ! PoisonPill
	  }
	  
	  "after one message per tick, average should one" in {
	    val counter = system.actorOf(Props( new MessageCounter))
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
	    val counter = system.actorOf(Props( new MessageCounter))
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
	
}
