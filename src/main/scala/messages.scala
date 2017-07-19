import com.github.nscala_time.time.Imports._
import scala.util.Try
import net.liftweb.json.JsonParser._

package hyperion {
    case class Message(nvpairs: Map[String, String])
	{
	  def set(name: String, value: String) = Message(nvpairs.updated(name, value))
	  
	  def withMessage(value: String) = set("MESSAGE", value)
	  
	  def apply(name: String) = nvpairs(name)

		def mergeWithPrefix(prefix: String, message: Message): Message = {
			var newNvpairs = nvpairs;
			message.nvpairs map {case(key, value) => {
					newNvpairs = newNvpairs.updated(prefix + key, value)
			}}
			Message(newNvpairs)
		}
	}

	trait MessageParser {
		def apply(message: String) : Message
	}
	
	object Message {
	  
      def empty = Message(Map[String, String]().empty)
      
      def withMessage(value:String) = empty.withMessage(value)
    }

	case class JsonParserContext(fieldSeparators: List[Int], prefix: String)
	{
		def extendPrefix(field: String) = {
			if (prefix == "") {
				JsonParserContext(List[Int](), field + ".")
			}	else {
				if (fieldSeparators.length == 0) {
					JsonParserContext(List[Int](prefix.length), prefix + field + ".")
				} else {
					JsonParserContext(fieldSeparators.head + field.length  + 1:: fieldSeparators, prefix + field + ".")
				}
			}
		}

		def removePrefix = {
			fieldSeparators.length match {
				case 0 => JsonParserContext(List[Int](), "")
				case 1 => JsonParserContext(List[Int](), prefix.substring(0, fieldSeparators.head))
				case _ => JsonParserContext(fieldSeparators.tail, prefix.substring(0, fieldSeparators.head))
			}
		}
	}

  object parseJsonMessage extends MessageParser {
		def apply(message: String) = {
			val parser = (p: Parser) => {
				def parse(context :JsonParserContext, message: Message): Message = p.nextToken match {
					case FieldStart(fieldname) => p.nextToken match {
						case StringVal(str) => parse(context, message.set(context.prefix + fieldname, str))
						case IntVal(int) => parse(context, message.set(context.prefix + fieldname, int.toString()))
						case BoolVal(bool) => parse(context, message.set(context.prefix + fieldname, bool.toString()))
						case DoubleVal(double) => parse(context, message.set(context.prefix + fieldname, double.toString()))
						case OpenObj => parse(context.extendPrefix(fieldname), message)
					}
					case CloseObj => parse(context.removePrefix, message)
					case End => message
					case _ => parse(context, message)
				}

				parse(JsonParserContext(List[Int](), ""), Message.empty)
			}

			parse(message, parser).set("DATE",  (System.currentTimeMillis / 1000).toString)
		}
  }

	object parseNoParser extends MessageParser{
		def apply(message: String) = Message.withMessage(message)
	}

	object parserFactory {
		def apply(parserName: String) = parserName match {
			case "syslog" => parseSyslogMessage
			case "json" => parseJsonMessage
			case "raw" => parseNoParser
		}
	}
		
	object parseSyslogMessage extends MessageParser {
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
			 Message(parsePrio(message))
		 }
	   
	   def parsePrio(message: String) =
	   {
	     val prioregexp(prio, leftover) = message
	     parseDate(leftover).updated("PRIO", prio)
	   }
	
	   def parseDate(message: String) : Map[String, String] =
	   {
	     val dateregexp(date, leftover) = message
	     val epoch = Try(dateformatter.parseDateTime(date).getMillis)
	     if (epoch.isFailure) {
				 val epoch2 = Try(dateformatterv2.parseDateTime(date).getMillis)
				 if (epoch2.isFailure) {
					 val epoch3 = (dateformatterv3.parseDateTime(date).getMillis)
					 return parseHost(leftover).updated("DATE", epoch3 toString)
				 }
				 else {
					 return parseHost(leftover).updated("DATE", epoch2.get toString)
				 }
			 }
	     else
	        return parseHost(leftover).updated("DATE",epoch.get toString)


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
