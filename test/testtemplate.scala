/**
  * Created by talien on 4/29/17.
  */
import org.scalatest._
import hyperion._
import com.github.nscala_time.time.Imports._
import org.joda.time.format._

class TestTemplate extends FlatSpec {
  it should "be able to format a literal" in {
    val template = new MessageTemplate("alma")
    assert(template.format(Message.empty) == "alma")
  }

  it should "be able to format a simple variable" in {
    val template = new MessageTemplate("$MESSAGE")
    assert(template.format(Message.empty.withMessage("alma")) == "alma")
  }

  it should "be able to format a literal and a variable" in {
    val template = new MessageTemplate("korte $MESSAGE")
    assert(template.format(Message.empty.withMessage("alma")) == "korte alma")
  }

  it should "be able to format multiple literals and variables" in {
    val template = new MessageTemplate("korte $MESSAGE citrom $MESSAGE")
    assert(template.format(Message.empty.withMessage("alma")) == "korte alma citrom alma")
  }

  it should "be able to use double dollar as escaping dollar sign" in {
    val template = new MessageTemplate("$$")
    assert(template.format(Message.empty.withMessage("alma")) == "$")
  }

  it should "be able to use double dollar as escaping dollar sign with literal" in {
    val template = new MessageTemplate("$$MESSAGE")
    assert(template.format(Message.empty.withMessage("alma")) == "$MESSAGE")
  }

  it should "treat non-existent variable as empty string" in {
    val template = new MessageTemplate("$alma")
    assert(template.format(Message.empty.withMessage("alma")) == "")
  }

  it should "honor escape sequence as terminator of variable" in {
    val template = new MessageTemplate("$MESSAGE\n")
    assert(template.format(Message.empty.withMessage("alma")) == "alma\n")
  }

  it should "use brackets as indicators of variable name" in {
    val template = new MessageTemplate("${alma.korte}")
    assert(template.format(Message.empty.set("alma.korte","barack")) == "barack")
  }

  //FIXME: it will definitely break in a TZ different than GMT + 1
  it should "treat DATE macro specially" in {
    val template = new MessageTemplate("$DATE")
    assert(template.format(Message.empty.set("DATE","0")) == "1970-01-01T01:00:00.000+01:00")
  }
}

class TestTemplatePerformance extends FlatSpec {
  it should "be fast" in {
    val template = new MessageTemplate("<$PRIO> $DATE $HOST $PROGRAM $MESSAGE \n")
    val start = DateTime.now.getMillis
    for( a <- 1 to 100000) {
      template.format(Message.empty.withMessage("alma").set("DATE", "0").set("HOST", "localhost").set("PRIO","1").set("PROGRAM","test"))
    }
    val end = DateTime.now.getMillis
    println(end - start)
  }
}
