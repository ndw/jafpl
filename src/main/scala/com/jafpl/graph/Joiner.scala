package com.jafpl.graph

private[jafpl] class Joiner(override val graph: Graph, val ordered: Boolean) extends Node(graph, None, None) {

  override def inputsOk() = true

  override def outputsOk(): Boolean = {
    (outputs.size == 1) && outputs.contains("result")
  }
}