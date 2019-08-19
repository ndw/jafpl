package com.jafpl.runtime

import akka.actor.ActorRef
import com.jafpl.exceptions.JafplException
import com.jafpl.graph.{EmptySource, Node}
import com.jafpl.messages.Message
import com.jafpl.runtime.GraphMonitor.{GClose, GFinished}

private[runtime] class EmptySourceActor(private val monitor: ActorRef,
                                        override protected val runtime: GraphRuntime,
                                        override protected val node: EmptySource)
  extends NodeActor(monitor, runtime, node)  {

  var hasBeenReset = false
  logEvent = TraceEvent.EMPTY

  override protected def input(from: Node, fromPort: String, port: String, item: Message): Unit = {
    trace("INPUT", s"$node.$fromPort -> $port", logEvent)
    throw JafplException.inputOnEmptySource(from.toString, fromPort, port, item.toString, node.location)
  }

  override protected def reset(): Unit = {
    trace("RESET", s"$node", logEvent)
    started = true
    hasBeenReset = true
    openInputs.clear()
  }

  override protected def run(): Unit = {
    trace("RUN", s"$node", logEvent)
    node.state = NodeState.FINISHED
    monitor ! GClose(node, "result")
    monitor ! GFinished(node)
  }

  override protected def traceMessage(code: String, details: String): String = {
    s"$code          ".substring(0, 10) + details + " [EmptySource]"
  }
}
