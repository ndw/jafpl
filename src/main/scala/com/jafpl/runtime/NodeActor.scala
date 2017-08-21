package com.jafpl.runtime

import akka.actor.{Actor, ActorRef}
import akka.event.Logging
import com.jafpl.exceptions.{GraphException, PipelineException}
import com.jafpl.graph.{ContainerEnd, Node}
import com.jafpl.messages.BindingMessage
import com.jafpl.runtime.GraphMonitor.{GClose, GException, GFinished, GStopped}
import com.jafpl.runtime.NodeActor.{NAbort, NCatch, NCheckGuard, NChildFinished, NClose, NContainerFinished, NException, NGuardResult, NInitialize, NInput, NReset, NStart, NStop, NTraceDisable, NTraceEnable, NViewportFinished}
import com.jafpl.steps.{Consumer, PortBindingSpecification}

import scala.collection.mutable

private[runtime] object NodeActor {
  case class NInitialize()
  case class NStart()
  case class NAbort()
  case class NStop()
  case class NCatch(cause: Throwable)
  case class NReset()
  case class NInput(port: String, item: Any)
  case class NClose(port: String)
  case class NChildFinished(otherNode: Node)
  case class NContainerFinished()
  case class NViewportFinished(buffer: List[Any])
  case class NTraceEnable(event: String)
  case class NTraceDisable(event: String)
  case class NCheckGuard()
  case class NGuardResult(when: Node, pass: Boolean)
  case class NException(cause: Throwable)
}

private[runtime] class NodeActor(private val monitor: ActorRef,
                                 private val runtime: GraphRuntime,
                                 private val node: Node) extends Actor {
  protected val log = Logging(context.system, this)
  protected val openInputs = mutable.HashSet.empty[String]
  protected val openBindings = mutable.HashSet.empty[String]
  protected var readyToRun = false
  protected val traces = mutable.HashSet.empty[String]
  protected val cardinalities = mutable.HashMap.empty[String, Long]
  protected var proxy = Option.empty[ConsumingProxy]

  def this(monitor: ActorRef, runtime: GraphRuntime, node: Node, proxy: Option[ConsumingProxy]) {
    this(monitor, runtime, node)
    this.proxy = proxy
  }

  protected def traceEnabled(event: String): Boolean = {
    traces.contains(event) || runtime.dynamicContext.traceEnabled(event)
  }

  protected def trace(message: String, event: String): Unit = {
    trace("info", message, event)
  }

  protected def trace(level: String, message: String, event: String): Unit = {
    if (traceEnabled(event)) {
      level match {
        case "info" => log.info(message)
        case "debug" => log.debug(message)
        case _ => log.warning(message)
      }
    }
  }

  protected def initialize(): Unit = {
    for (input <- node.inputs) {
      openInputs.add(input)
    }
    for (input <- node.bindings) {
      openBindings.add(input)
    }
  }

  protected def reset(): Unit = {
    readyToRun = false
    openInputs.clear()
    for (input <- node.inputs) {
      openInputs.add(input)
    }
    openBindings.clear()
    for (input <- node.bindings) {
      openBindings.add(input)
    }
    cardinalities.clear()
    if (proxy.isDefined) {
      proxy.get.reset()
    }
  }

  protected def start(): Unit = {
    readyToRun = true
    runIfReady()
  }

  protected def abort(): Unit = {
    if (node.step.isDefined) {
      trace(s"RABRT $node", "StepExec")
      try {
        node.step.get.abort()
      } catch {
        case cause: Throwable =>
          monitor ! GException(Some(node), cause)
      }
    } else {
      trace(s"XABRT $node", "StepExec")
    }
    monitor ! GFinished(node)
  }

  protected def stop(): Unit = {
    if (node.step.isDefined) {
      trace(s"RSTOP $node", "Stopping")
      try {
        node.step.get.stop()
      } catch {
        case cause: Throwable =>
          monitor ! GException(Some(node), cause)
      }
    } else {
      trace(s"XSTOP $node", "Stopping")
    }

    monitor ! GStopped(node)
  }

  private def runIfReady(): Unit = {
    trace(s"RNIFR $node $readyToRun ${openInputs.isEmpty} ${openBindings.isEmpty}", "StepExec")

    if (readyToRun) {
      if (openInputs.isEmpty && openBindings.isEmpty) {
        run()
      } else {
        for (port <- openInputs) {
          trace(s"..... $port", "StepExec")
        }
        for (varname <- openBindings) {
          trace(s"....B $varname", "StepExec")
        }
      }
    }
  }

  protected def run(): Unit = {
    var threwException = false

    if (node.step.isDefined) {
      trace(s"RSTEP $node", "StepExec")
      try {
        node.step.get.run()
        if (proxy.isDefined) {
          for (output <- node.outputs) {
            if (!output.startsWith("#")) {
              node.step.get.outputSpec.checkCardinality(output,proxy.get.cardinality(output))
            }
          }
        }
      } catch {
        case cause: Throwable =>
          threwException = true
          println("BANG: "+ node)
          monitor ! GException(Some(node), cause)
      }
    } else {
      trace(s"XSTEP $node", "StepExec")
    }

    if (!threwException) {
      for (output <- node.outputs) {
        monitor ! GClose(node, output)
      }
      monitor ! GFinished(node)
    }
  }

  protected def close(port: String): Unit = {
    if (node.step.isDefined && node.step.get.inputSpec != PortBindingSpecification.ANY
        && !port.startsWith("#")) {
      try {
        node.step.get.inputSpec.checkCardinality(port, cardinalities.getOrElse(port, 0L))
      } catch {
        case cause: Throwable =>
          println("BANG in " + node)
          monitor ! GException(Some(node), cause)
        case _: Throwable => Unit
      }
    }
    openInputs -= port
    runIfReady()
  }

  protected def input(port: String, item: Any): Unit = {
    if (port == "#bindings") {
      item match {
        case binding: BindingMessage =>
          trace(s"BINDING: $node: ${binding.name}=${binding.item}", "Bindings")
          if (node.step.isDefined) {
            node.step.get.receiveBinding(binding.name, binding.item)
          }
          openBindings -= binding.name
        case _ => throw new GraphException("Unexpected item on #bindings port")
      }
    } else {
      val card = cardinalities.getOrElse(port, 0L) + 1L
      cardinalities.put(port, card)
      if (node.step.isDefined) {
        node.step.get.receive(port, item)
      }
    }
  }

  protected def checkGuard(): Unit = {
    throw new GraphException("Attempted to check guard on something that isn't a when")
  }

  protected def guardResult(when: Node, pass: Boolean): Unit = {
    throw new GraphException("Attempted to pass guard result to something that isn't a when")
  }

  final def receive: PartialFunction[Any, Unit] = {
    case NInitialize() =>
      trace(s"INITL $node", "StepMessages")
      initialize()

    case NInput(port, item) =>
      trace(s"RCVON $node.$port", "StepIO")
      input(port, item)

    case NClose(port) =>
      trace(s"CLOSE $node.$port", "StepIO")
      close(port)

    case NStart() =>
      trace(s"RUNST $node", "StepMessages")
      start()

    case NAbort() =>
      trace(s"ABORT $node", "StepMessages")
      abort()

    case NStop() =>
      trace(s"STOPN $node", "Stopping")
      stop()

    case NCatch(cause) =>
      trace(s"RUNCT $node", "StepMessages")
      this match {
        case catchStep: CatchActor =>
          catchStep.start(cause)
        case _ =>
          monitor ! GException(Some(node),
            new PipelineException("notcatch", "Attempt to send exception to something that's not a catch"))
      }

    case NReset() =>
      trace(s"RESET $node", "StepMessages")
      reset()

    case NContainerFinished() =>
      node match {
        case end: ContainerEnd =>
          trace(s"FINSH ${end.start.get}", "StepMessages")
        case _ =>
          trace(s"FINSH $node", "StepMessages")
      }

      this match {
        case start: StartActor =>
          start.finished()
        case _ =>
          monitor ! GException(Some(node),
            new PipelineException("notstart", "Container finish message sent to " + node))
      }

    case NViewportFinished(buffer) =>
      node match {
        case end: ContainerEnd =>
          trace(s"FINSH ${end.start.get}", "StepMessages")
        case _ =>
          trace(s"FINSH $node", "StepMessages")
      }

      this match {
        case start: ViewportActor =>
          start.returnItems(buffer)
          start.finished()
        case _ => throw new GraphException("Container finish message sent to " + node)
      }

    case NChildFinished(otherNode) =>
      trace(s"CFNSH $otherNode", "StepMessages")
      this match {
        case end: EndActor =>
          end.finished(otherNode)
        case _ => throw new GraphException("Child finish message sent to " + node)
      }

    case NCheckGuard() =>
      trace(s"GUARD $this", "StepMessages")
      checkGuard()

    case NGuardResult(when, pass) =>
      trace(s"GRSLT $when: $pass", "StepMessages")
      guardResult(when, pass)

    case NException(cause) =>
      trace(s"EXCPT $node $cause $this", "StepMessages")
      this match {
        case trycatch: TryCatchActor =>
          trycatch.exception(cause)
        case _ =>
          monitor ! GException(node.parent, cause)
      }

    case NTraceEnable(event) =>
      trace(s"TRACE $event", "Traces")
      traces += event

    case NTraceDisable(event) =>
      trace(s"XTRCE $event", "Traces")
      traces -= event

    case m: Any => log.error("Unexpected message: {}", m)
  }
}