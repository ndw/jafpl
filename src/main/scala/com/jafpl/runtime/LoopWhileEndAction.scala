package com.jafpl.runtime

import com.jafpl.graph.{ContainerEnd, LoopWhileStart}
import com.jafpl.messages.Message
import com.jafpl.runtime.NodeState.NodeState
import com.jafpl.util.TraceEventManager

import scala.collection.mutable

class LoopWhileEndAction(override val node: ContainerEnd) extends EndAction(node) {
  private var startAction: LoopWhileAction = _
  private val loopStart: LoopWhileStart = node.start.get.asInstanceOf[LoopWhileStart]
  private val buffer = mutable.HashMap.empty[String, Message]

  def loopStartAction: LoopWhileAction = startAction
  def loopStartAction_=(start: LoopWhileAction): Unit = {
    startAction = start
  }

  override def receive(port: String, message: Message): Unit = {
    if (port == "test") {
      // This is magic, it needs to go back to the loop start
      startAction.receive("test", message)
    } else {
      if (loopStart.returnAll) {
        // Everything else just goes through
        super.receive(port, message)
      } else {
        buffer.put(port, message)
      }
    }
  }

  override def run(): Unit = {
    tracer.trace(s"RUN   $this ************************************************************", TraceEventManager.RUN)
    if (startAction.done && !loopStart.returnAll) {
      for (port <- buffer.keys) {
        super.receive(port, buffer(port))
      }
      buffer.clear()
    }
    scheduler.finish(node)
  }

  override def reset(state: NodeState): Unit = {
    super.reset(state)
    buffer.clear()
  }
}
