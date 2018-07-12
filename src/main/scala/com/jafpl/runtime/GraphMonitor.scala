package com.jafpl.runtime

import java.time.{Duration, Instant}

import akka.actor.{Actor, ActorRef, PoisonPill}
import akka.event.Logging
import com.jafpl.exceptions.JafplException
import com.jafpl.graph.{ContainerEnd, Graph, Node}
import com.jafpl.messages.{ItemMessage, Message}
import com.jafpl.runtime.GraphMonitor.{GAbort, GAbortExecution, GCatch, GCheckGuard, GClose, GException, GFinally, GFinished, GFinishedViewport, GGuardResult, GLoop, GNode, GOutput, GReset, GRun, GRunFinally, GStart, GStop, GStopped, GTrace, GWatchdog}
import com.jafpl.runtime.NodeActor.{NAbort, NCatch, NCheckGuard, NChildFinished, NClose, NContainerFinished, NException, NFinally, NGuardResult, NInitialize, NInput, NLoop, NReset, NRunFinally, NStart, NStop, NViewportFinished}

import scala.collection.mutable

private[runtime] object GraphMonitor {
  case class GNode(node: Node, actor: ActorRef)
  case class GRun()
  case class GAbortExecution()
  case class GReset(node: Node)
  case class GStart(node: Node)
  case class GCatch(node: Node, cause: Throwable)
  case class GFinally(node: Node)
  case class GRunFinally(node: Node, cause: Option[Throwable])
  case class GException(node: Option[Node], cause: Throwable)
  case class GOutput(node: Node, port: String, item: Message)
  case class GLoop(node: Node, item: ItemMessage)
  case class GClose(node: Node, port: String)
  case class GFinished(node: Node)
  case class GFinishedViewport(node: Node, buffer: List[Message])
  case class GAbort(node: Node)
  case class GStop(node: Node)
  case class GStopped(node: Node)
  case class GTrace(event: String)
  case class GCheckGuard(node: Node)
  case class GGuardResult(when: Node, pass: Boolean)
  case class GWatchdog(millis: Long)
}

private[runtime] class GraphMonitor(private val graph: Graph, private val runtime: GraphRuntime) extends Actor {
  val log = Logging(context.system, this)
  protected val unfinishedNodes = mutable.HashSet.empty[Node]
  protected val unstoppedNodes = mutable.HashSet.empty[Node]
  private val actors = mutable.HashMap.empty[Node, ActorRef]
  private var lastMessage = Instant.now()
  private var exception: Option[Throwable] = None

  protected def trace(message: String, event: String): Unit = {
    trace("info", message, event)
  }

  protected def trace(level: String, message: String, event: String): Unit = {
    // We don't use the traceEventManager.trace() call because we want to use the Akka logger
    if (runtime.traceEventManager.traceEnabled(event)) {
      level match {
        case "info" => log.info(message)
        case "debug" => log.debug(message)
        case _ => log.warning(message)
      }
    }
  }

  private def fmtSender(): String = {
    var str = sender().toString
    var pos = str.indexOf("/user/")
    str = str.substring(pos+6)
    pos = str.indexOf("#")
    if (pos > 0) {
      str = str.substring(0, pos)
    }
    str
  }

  def watchdog(millis: Long): Unit = {
    trace(s"WATCHDOG $millis", "Watchdog")
    for (node <- unfinishedNodes) {
      trace(s"-------- $node", "Watchdog")
    }
    crashAndBurn(JafplException.watchdogTimeout())
  }

  def stopPipeline(): Unit = {
    trace(s"STOPPING", "Run")
    for (node <- unstoppedNodes) {
      if (node.parent.isEmpty) {
        actors(node) ! NStop()
      }
    }
  }

  def stoppedStep(node: Node): Unit = {
    unstoppedNodes -= node
    actors(node) ! PoisonPill
    if (unstoppedNodes.isEmpty) {
      if (exception.isDefined) {
        runtime.finish(exception.get)
      } else {
        runtime.finish()
      }
    }
  }

  def crashAndBurn(cause: Throwable): Unit = {
    trace(s"CRASHBRN $cause", "Exceptions")
    exception = Some(cause)
    stopPipeline()
  }

  final def receive: PartialFunction[Any, Unit] = {
    case GWatchdog(millis) =>
      val ns = Duration.between(lastMessage, Instant.now()).toMillis
      if (ns > millis) {
        watchdog(millis)
      }

    case GRun() =>
      lastMessage = Instant.now()
      trace("RUNGRAPH", "Run")
      for (node <- graph.nodes) {
        trace(s"INITLIZE $node", "Run")
        actors(node) ! NInitialize()
        if (node.parent.isEmpty) {
          unfinishedNodes += node
        }
      }
      for (node <- unfinishedNodes) {
        trace(s"STARTTOP $node", "Run")
        actors(node) ! NStart()
      }

    case GStart(node) =>
      lastMessage = Instant.now()
      trace(s"STRTNODE $node", "Run")
      actors(node) ! NStart()

    case GAbort(node) =>
      lastMessage = Instant.now()
      trace(s"ABRTNODE $node", "Run")
      actors(node) ! NAbort()

    case GStop(node) =>
      lastMessage = Instant.now()
      trace(s"STOPNODE $node", "Stopping")
      actors(node) ! NStop()

    case GStopped(node) =>
      lastMessage = Instant.now()
      trace(s"STOPPED⯃ $node", "Stopping")
      stoppedStep(node)

    case GCatch(node, cause) =>
      lastMessage = Instant.now()
      trace(s"STRTCTCH $node", "Run")
      actors(node) ! NCatch(cause)

    case GFinally(node) =>
      lastMessage = Instant.now()
      trace(s"TELLFINL $node", "Run")
      actors(node) ! NFinally()

    case GRunFinally(node, cause) =>
      lastMessage = Instant.now()
      trace(s"STRTFINL $node", "Run")
      actors(node) ! NRunFinally(cause)

    case GReset(node) =>
      lastMessage = Instant.now()
      trace(s"RESETNOD $node", "Run")
      actors(node) ! NReset()

    case GOutput(node, port, item) =>
      lastMessage = Instant.now()

      item match {
        case msg: ItemMessage =>
          for (inj <- node.outputInjectables) {
            if (inj.port == port) {
              inj.run(msg)
            }
          }
        case _ => Unit
      }

      if (node.hasOutputEdge(port)) {
        val edge = node.outputEdge(port)
        trace(s"SENDOUT→ $node.$port → ${edge.to}.${edge.toPort} from ${fmtSender()}", "StepIO")
        actors(edge.to) ! NInput(node, port, edge.toPort, item)
      } else {
        trace(s"DROPOUT↴ $node.$port from ${fmtSender()}", "StepIO")
      }

    case GLoop(node, item) =>
      lastMessage = Instant.now()
      trace(s"LOOPTOP↑ ($item)", "StepIO")
      actors(node) ! NLoop(item)

    case GClose(node, port) =>
      lastMessage = Instant.now()
      trace(s"CLOSEOUT $node.$port from ${fmtSender()}", "StepIO")
      val edge = node.outputEdge(port)
      actors(edge.to) ! NClose(edge.toPort)

    case GCheckGuard(node) =>
      lastMessage = Instant.now()
      trace(s"CHKGUARD $node", "Choose")
      actors(node) ! NCheckGuard()

    case GGuardResult(when, pass) =>
      lastMessage = Instant.now()
      trace(s"GRDRESLT $when: $pass", "Choose")
      actors(when.parent.get) ! NGuardResult(when, pass)

    case GFinished(node) =>
      lastMessage = Instant.now()
      trace(s"FINISHED $node", "Run")

      if (unfinishedNodes.contains(node)) {
        unfinishedNodes -= node
        if (unfinishedNodes.isEmpty) {
          stopPipeline()
        }
      }

      node match {
        case end: ContainerEnd =>
          trace(s"TLLSTART $node finished → ${end.start.get}", "Run")
          actors(end.start.get) ! NContainerFinished()
        case _ =>
          if (node.parent.isDefined) {
            val end = node.parent.get.containerEnd
            trace(s"TLLPARNT $node finished → ${end.start.get}", "Run")
            actors(end) ! NChildFinished(node)
          }
      }

    case GFinishedViewport(node, buffer) =>
      lastMessage = Instant.now()
      trace(s"FINISHED $node", "Run")

      node match {
        case end: ContainerEnd =>
          trace(s"TLLSTART $node finished → ${end.start.get}", "Run")
          actors(end.start.get) ! NViewportFinished(buffer)
        case _ =>
          if (node.parent.isDefined) {
            val end = node.parent.get.containerEnd
            trace(s"TLLPARNT $node finished → ${end.start.get}", "Run")
            actors(end) ! NChildFinished(node)
          }
      }

    case GTrace(event) =>
      lastMessage = Instant.now()
      trace(s"ADDTRACE $event", "Traces")
      runtime.traceEventManager.enableTrace(event)

    case GNode(node,actor) =>
      lastMessage = Instant.now()
      trace(s"+ADDNODE $node", "AddNode")
      actors.put(node, actor)
      unstoppedNodes += node

    case GException(node, cause) =>
      lastMessage = Instant.now()
      trace(s"EXCPTION $node $cause", "Exceptions")

      if (node.isDefined) {
        actors(node.get) ! NException(cause)
      } else {
        crashAndBurn(cause)
      }

    case GAbortExecution() =>
      lastMessage = Instant.now()
      trace(s"STOPPIPE", "Run")
      stopPipeline()

    case m: Any =>
      lastMessage = Instant.now()
      log.error(s"UNEXPECT $m")
  }
}
