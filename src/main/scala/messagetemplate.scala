/**
  * Created by talien on 4/29/17.
  */
import com.github.nscala_time.time.Imports._
import org.joda.time.format._

package hyperion {

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
      if (text(startPosition + 1) == '{')
      {
        val (variableName, endOfVariable) = parseBracketMacro(text, startPosition)
        return resolveSpecialMacros(variableName, endOfVariable) 
      } else {
        val (variableName, endOfVariable) = parseSingleMacro(text, startPosition)
        return resolveSpecialMacros(variableName, endOfVariable) 
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
          }
        }
      )
    }
  }

}
