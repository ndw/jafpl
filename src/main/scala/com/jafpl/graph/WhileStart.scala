package com.jafpl.graph

private[jafpl] class WhileStart(override val graph: Graph,
                                override protected val end: ContainerEnd,
                                override val userLabel: Option[String],
                                val testexpr: String)
  extends ContainerStart(graph, end, userLabel) {

  override def inputsOk(): Boolean = {
    var hasSource = false

    if (inputs.nonEmpty) {
      var valid = true
      for (port <- inputs) {
        if (port != "#bindings" && port != "source") {
          println("Invalid binding on " + this + ": " + port)
          valid = false
        }
        hasSource = hasSource || (port == "source")
      }
      valid && hasSource
    } else {
      true
    }
  }

}
