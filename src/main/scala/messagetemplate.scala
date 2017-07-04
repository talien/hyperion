/**
  * Created by talien on 4/29/17.
  */
import com.github.nscala_time.time.Imports.{DateTime}
import org.joda.time.format._
import net.liftweb.json.JsonAST._
import net.liftweb.json.Extraction._
import net.liftweb.json.Printer._
import scala.collection.immutable.ListMap

package hyperion {

  trait TemplateFunction {
    def apply(msg: Message) : String
  }

  abstract class TemplateItem() {
    def process(msg: Message) : String
  }
  case class Literal(literal: String) extends TemplateItem {
    def process(msg: Message) : String = literal
  }
  case class Variable(variable: String) extends TemplateItem {
    def process(msg: Message) : String = msg.nvpairs.getOrElse(variable, "")
  }

  abstract class AbstractDateMacro extends TemplateItem {
    def fmt : DateTimeFormatter

    def process(msg: Message) : String = {
      val epoch = msg.nvpairs("DATE") toLong
      val date = new DateTime(epoch)
      return fmt.print(date)
    }
  }

  case class DateMacro() extends AbstractDateMacro {
    val format = ISODateTimeFormat.dateTime()
    def fmt = format

  }

  case class YearMacro() extends AbstractDateMacro {
    val format = DateTimeFormat.forPattern("YYYY")
    def fmt = format
  }

  case class MonthMacro() extends AbstractDateMacro {
    val format = DateTimeFormat.forPattern("MM")
    def fmt = format
  }

  case class DayMacro() extends AbstractDateMacro {
    val format = DateTimeFormat.forPattern("dd")
    def fmt = format
  }

  case class HourMacro() extends AbstractDateMacro {
    val format = DateTimeFormat.forPattern("HH")
    def fmt = format
  }

  case class MinuteMacro() extends AbstractDateMacro {
    val format = DateTimeFormat.forPattern("mm")
    def fmt = format
  }

  case class SecondsMacro() extends AbstractDateMacro {
    val format = DateTimeFormat.forPattern("ss")
    def fmt = format
  }
 
  case class Function(function: TemplateFunction) extends TemplateItem {
    def process(msg: Message) : String = {
      return function(msg)
    }
  }

  class EchoTemplateFunction(positionalParams : List[String]) extends TemplateFunction {
    val field = positionalParams(0)
    def apply(msg: Message) : String = {
       return msg(field)
    }
  }

  class FormatJsonTemplateFunction(positionalParams : List[String]) extends TemplateFunction {
    implicit val formats = net.liftweb.json.DefaultFormats

    def insertValueImpl(message: Map[String, Any], path: List[String], value: String): Map[String, Any] = {
      if (path.length == 1) {
        return message.updated(path(0), value)
      }
      val next = if (message.contains(path(0))) {
        message.get(path(0)).get.asInstanceOf[Map[String, Any]]
      } else {
        Map[String, Any]()
      }
      return message.updated(path(0), insertValueImpl(next, path.tail, value))
    }

    def insertValue(message: Map[String, Any], key: String, value: String) : Map[String, Any] = {
      val path = key.split('.').toList
      val res = insertValueImpl(message, path, value)
      return res
    }

    def transform(message: Message) : Map[String, Any] = {
      var res = Map[String, Any]()

      val map = ListMap(message.nvpairs.toSeq.sortWith(_._1 < _._1):_*)
      for ((key, value) <- map) {
        res = insertValue(res, key, value)
      }

      return res.toMap
    }

    def apply(msg: Message) : String = {
       return compact(render(decompose(transform(msg))))
    }
  }

  object FunctionFactory {
    def create(functionBody: String) : TemplateItem = {
       val items = functionBody.split(" ").toList
       val name = items(0)
       val positionalParameters = items.tail
       val function = name match {
           case "echo" => new EchoTemplateFunction(positionalParameters)
           case "format-json" => new FormatJsonTemplateFunction(positionalParameters)
           case _ => throw new Exception("No such template function!")
       }
       return Function(function);
    }
  }

  class MessageTemplate(template: String) {
    val templateItems: scala.collection.mutable.MutableList[TemplateItem] = scala.collection.mutable.MutableList[TemplateItem]();
    parse(template)

    def parseSingleMacro(text: String, startPosition: Int): Tuple2[String, Int] = {
        for (a <- startPosition + 1 to (text.length() - 1))
        {
          if (!text(a).isLetterOrDigit) {
            return (text.substring(startPosition + 1, a), a);
          }
        }
        return (text.substring(startPosition + 1), -1);
    }

    def parseTemplateFunction(text: String, startPosition: Int): Tuple2[TemplateItem, Int] = {
        val endParenthesis = text.indexOf(")", startPosition)
        if (endParenthesis >= 0) {
          val functionBody = text.substring(startPosition + 2, endParenthesis)
          val endPosition = endParenthesis + 1

          return (FunctionFactory.create(functionBody), endPosition)
        } else {
          throw new Exception("MessageParser: Unmatched closing parenthesis in template")
        }
       
    }

    def parseBracketMacro(text: String, startPosition: Int): Tuple2[String, Int] = {
        val endBracket = text.indexOf("}", startPosition)
        if (endBracket >= 0) {
          return (text.substring(startPosition + 2, endBracket), endBracket + 1)
        } else {
          throw new Exception("MessageParser: Unmatched closing bracket in template")
        }
    }

    def resolveSpecialMacros(variableName: String, endOfVariable: Int): Tuple2[TemplateItem, Int] = {

        variableName match {
          case "DATE" => (DateMacro(), endOfVariable)
          case "YEAR" => (YearMacro(), endOfVariable)
          case "MONTH" => (MonthMacro(), endOfVariable)
          case "DAY" => (DayMacro(), endOfVariable)
          case "HOUR" => (HourMacro(), endOfVariable)
          case "MINUTE" => (MinuteMacro(), endOfVariable)
          case "SECOND" => (SecondsMacro(), endOfVariable)
          case _ => (Variable(variableName), endOfVariable)
        }
    }

    def parseMacro(text: String, startPosition: Int): Tuple2[TemplateItem, Int] = {
      return text(startPosition + 1) match {
        case '{' =>
                val (variableName, endOfVariable) = parseBracketMacro(text, startPosition)
                resolveSpecialMacros(variableName, endOfVariable) 
             
        case '(' => parseTemplateFunction(text, startPosition) 
        case _ =>
                val (variableName, endOfVariable) = parseSingleMacro(text, startPosition)
                resolveSpecialMacros(variableName, endOfVariable) 
             
      }
    }

    def parse(template: String): Unit = {
      if (template.length == 0) return
      val variable = template.indexOf("$")
      if ((variable < template.length()) && (template.charAt(variable + 1) == '$')) {
        templateItems += Literal("$")
        parse(template.substring(variable + 2))
        return
      }
      if (variable > 0) {
        templateItems += Literal(template.substring(0, variable))
      }
      if (variable != -1) {
        val (templateItem, endOfVariable) = parseMacro(template, variable)
        templateItems += templateItem
        if (endOfVariable != -1)
          {
            parse(template.substring(endOfVariable))
          }
      }
      else {
        templateItems += Literal(template)
      }
    }

    def format(message: Message) :String = {

      var res = templateItems.foldLeft(new StringBuilder())( (x, templateItem) => {
          x.append(templateItem.process(message))
        }
      )
      return res.toString()
    }
  }

}
