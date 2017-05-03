import com.github.nscala_time.time.Imports._
import hyperion.parseSyslogMessage
import org.scalatest.FlatSpec

/**
  * Created by talien on 4/30/17.
  */
class TestMessage extends FlatSpec {
  it should "be able to parse a single line" in {
    val line = "<38>2013-11-11T01:01:31 localhost prg00000[1234]: seq: 0000009579, thread: 0000, runid: 1384128081, stamp: 2013-11-11T01:01:31 PADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADD"
    val result = parseSyslogMessage(line)
    assert(result("PRIO") == "38")
    assert(result("HOST") == "localhost")
    assert(result("PROGRAM") == "prg00000")
    assert(result("PID") == "[1234]")
  }

  it should "be able to parse with legacy time" in {
    val line = "<30>Nov 30 18:28:01 ubu1 avahi-daemon[26471]: Successfully called chroot()."
    val result = parseSyslogMessage(line)
    assert(result("PRIO") == "30")
    assert(result("HOST") == "ubu1")
    assert(result("PROGRAM") == "avahi-daemon")
    assert(result("PID") == "[26471]")
    assert(result("MESSAGE") == "Successfully called chroot().")
  }

  it should "be able to parse with legacy time and one-digit day" in {
    val line = "<30>Dec  2 18:28:01 ubu1 avahi-daemon[26471]: Successfully called chroot()."
    val result = parseSyslogMessage(line)
    assert(result("PRIO") == "30")
    assert(result("HOST") == "ubu1")
    assert(result("PROGRAM") == "avahi-daemon")
    assert(result("PID") == "[26471]")
    assert(result("MESSAGE") == "Successfully called chroot().")
  }

}

class TestMessageParsePerformance extends FlatSpec {
  it should "be fast" in {
    val line = "<38>2013-11-11T01:01:31 localhost prg00000[1234]: seq: 0000009579, thread: 0000, runid: 1384128081, stamp: 2013-11-11T01:01:31 PADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADD"
    val start = DateTime.now.getMillis
    for( a <- 1 to 100000) {
      val result = parseSyslogMessage(line)
    }
    val end = DateTime.now.getMillis
    println(end - start)
  }

  it should "be fast without date" in {
    val line = "localhost prg00000[1234]: seq: 0000009579, thread: 0000, runid: 1384128081, stamp: 2013-11-11T01:01:31 PADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADD"
    val start = DateTime.now.getMillis
    for( a <- 1 to 100000) {
      val result = parseSyslogMessage.parseHost(line)
    }
    val end = DateTime.now.getMillis
    println(end - start)
  }

  it should "be fast with only message" in {
    val line = "localhost prg00000[1234]: seq: 0000009579, thread: 0000, runid: 1384128081, stamp: 2013-11-11T01:01:31 PADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADDPADD"
    val start = DateTime.now.getMillis
    for( a <- 1 to 100000) {
      val result = parseSyslogMessage.parseMessagePart(line)
    }
    val end = DateTime.now.getMillis
    println(end - start)
  }


}
