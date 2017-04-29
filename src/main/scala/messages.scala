import com.github.nscala_time.time.Imports._
import scala.util.Try

package hyperion {
    case class Message(nvpairs: Map[String, String])
	{
	  def set(name: String, value: String) = Message(nvpairs.updated(name, value))
	  
	  def withMessage(value: String) = set("MESSAGE", value)
	  
	  def apply(name: String) = nvpairs(name)
	}
	
	object Message {
	  
      def empty = Message(Map[String, String]().empty)
      
      def withMessage(value:String) = empty.withMessage(value)
    }
		
	object parseMessage {
	   val dateformatter = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss")
	   val dateformatterv2 = DateTimeFormat.forPattern("MMM dd HH:mm:ss")
	   val dateformatterv3 = DateTimeFormat.forPattern("MMM  d HH:mm:ss")
	   val prioregexp = "<([0-9]*)>(.*)".r
	   val isodatepart = "[0-9A-Z:-]*"
	   val legacymonths = "(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)"
	   val legacydatepart = legacymonths + "[ ]+[0-9]+ [0-9:]+"
	   val dateregexp = ("(" + isodatepart  + "|"+ legacydatepart + ") (.*)").r
	   val hostregexp = "([0-9A-Za-z.-_]*) (.*)".r
	   val programregexp = "([^ \\[]*)(\\[[^\\]]+\\])*:* (.*)".r
	
	   def apply(message: String) = {
			 parsePrio(message)
		 }
	   
	   def parsePrio(message: String) =
	   {
	     val prioregexp(prio, leftover) = message
	     parseDate(leftover).updated("PRIO", prio)
	   }
	
	   def parseDate(message: String) =
	   {
	     val dateregexp(date, leftover) = message
	     val epoch = Try(dateformatter.parseDateTime(date).getMillis * 1000)
	     val epoch2 = Try(if (epoch.isFailure)
	        (dateformatterv2.parseDateTime(date).getMillis * 1000)
	     else
	        epoch.get)
	     val epoch3 = if (epoch2.isFailure)
	        (dateformatterv3.parseDateTime(date).getMillis * 1000)
	     else
	        epoch2.get   
	     parseHost(leftover).updated("DATE",epoch toString)
	   }
	
	   def parseHost(message: String) =
	   {
	    val hostregexp(host, leftover) = message
	    parseProgram(leftover).updated("HOST", host)
	   }
	
	   def parseProgram(message: String) =
	   {
	    val programregexp(program, pid, leftover) = message
	    parseMessagePart(leftover).updated("PROGRAM", program).updated("PID", pid)
	   }
	  
	  def parseMessagePart(message: String) =
	    Map[String, String]().empty.updated("MESSAGE", message)
	   
	}
}