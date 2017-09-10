package com.jafpl.primitive

import com.jafpl.exceptions.PipelineException
import com.jafpl.messages.{BindingMessage, ItemMessage, Message}
import com.jafpl.runtime.{ExpressionEvaluator, RuntimeConfiguration}
import org.slf4j.{Logger, LoggerFactory}

class PrimitiveExpressionEvaluator(config: RuntimeConfiguration) extends ExpressionEvaluator() {
  protected val logger: Logger = LoggerFactory.getLogger(this.getClass)

  override def value(expr: Any, context: List[Message], bindings: Map[String,Message]): Any = {
    if (context.size > 1) {
      throw new PipelineException("badconext", "Context contains more than one item", None)
    }

    val strexpr = expr match {
      case str: String => str
      case _ => throw new PipelineException("unexpected", s"Unexpected object as expression value: $expr", None)
    }

    val patn = "(\\S+)\\s*([-+*/])\\s*(\\S+)".r
    val digits = "([0-9]+)".r
    strexpr match {
      case patn(left, op, right) =>
        val leftv = left match {
          case digits(num) => num.toLong
          case _ => numberFromBinding(bindings(left))
        }
        val rightv = right match {
          case digits(num) => num.toLong
          case _ => numberFromBinding(bindings(right))
        }
        val result = op match {
          case "-" => leftv - rightv
          case "+" => leftv + rightv
          case "*" => leftv * rightv
          case "/" => leftv / rightv
        }
        if (config.traceEnabled("ExprEval")) {
          logger.info(s"COMPUTED $strexpr = $result")
        }
        result
      case _ =>
        logger.warn("Expression did not match pattern: returning expression string as value: " + strexpr)
        strexpr
    }
  }

  private def numberFromBinding(message: Message): Long = {
    message match {
      case item: ItemMessage =>
        item.item.toString.toLong
      case _ =>
        throw new IllegalArgumentException("Binding message is not a number")
    }
  }

  override def booleanValue(expr: Any, context: List[Message], bindings: Map[String,Message]): Boolean = {
    if (context.size > 1) {
      throw new PipelineException("badconext", "Context contains more than one item", None)
    }

    val strexpr = expr match {
      case str: String => str
      case _ => throw new PipelineException("unexpected", s"Unexpected object as expression value: $expr", None)
    }

    val patn = ". ([<=>]) ([0-9]+)".r
    strexpr match {
      case patn(cond, num) =>
        val value = num.toLong
        if (context.nonEmpty) {
          context.head match {
            case item: ItemMessage =>
              item.item match {
                case cnum: Long =>
                  cond match {
                    case "<" => cnum < value
                    case ">" => cnum > value
                    case _ => cnum == value
                  }
                case cnum: Int =>
                  cond match {
                    case "<" => cnum < value
                    case ">" => cnum > value
                    case _ => cnum == value
                  }
                case _ => false
              }
            case _ => false
          }
        } else {
          false
        }
      case _ =>
        ! ( (strexpr == "") || (strexpr == "false") || (strexpr == "0") )
    }
  }
}
