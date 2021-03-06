package com.jafpl.runtime

import com.jafpl.graph.PipelineStart
import com.jafpl.messages.Message

class PipelineAction(override val node: PipelineStart) extends ContainerAction(node) {
  override def run(): Unit = {
    super.run()
    startChildren()

    for (port <- receivedPorts) {
      for (message <- received(port)) {
        scheduler.receive(node, port, message)
      }
    }

    scheduler.finish(node)
  }
}
