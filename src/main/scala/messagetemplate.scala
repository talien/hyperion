/**
  * Created by talien on 4/29/17.
  */
import com.github.nscala_time.time.Imports._
import org.joda.time.format._
import net.liftweb.json.JsonAST._
import net.liftweb.json.Extraction._
import net.liftweb.json.Printer._


package hyperion {


  trait TemplateFunction {
    def apply(msg: Message) : String
  }

  class TemplateItem()
  case class Literal(literal: String) extends TemplateItem
  case class Variable(variable: String) extends TemplateItem
  case class DateMacro() extends TemplateItem {
    def process(msg: Message) : String = {
      val epoch = msg.nvpairs("DATE") toLong
      val date = new DateTime(epoch)
      val fmt = ISODateTimeFormat.dateTime()
      return fmt.print(date)
    }
  }
 
  case class Function(function: TemplateFunction) extends TemplateItem

  class EchoTemplateFunction(positionalParams : List[String]) extends TemplateFunction {
    val field = positionalParams(0)
    def apply(msg: Message) : String = {
       return msg(field)
    }
  }

  class FormatJsonTemplateFunction(positionalParams : List[String]) extends TemplateFunction {
    implicit val formats = net.liftweb.json.DefaultFormats

    def apply(msg: Message) : String = {
       return compact(render(decompose(msg.nvpairs)))
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
        if (variableName == "DATE"){
          return (DateMacro(), endOfVariable)
        } else {
          return (Variable(variableName), endOfVariable)
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

    def format(message: Message) = {
      templateItems.foldLeft("")( (x, templateItem) => {
         templateItem match {
            case Literal(literal) => x + literal
            case Variable(variable) => x + message.nvpairs.getOrElse(variable, "")
            case d : DateMacro => x + d.process(message)
            case Function(f: TemplateFunction) => x + f(message)
          }
        }
      )
    }
  }

}
