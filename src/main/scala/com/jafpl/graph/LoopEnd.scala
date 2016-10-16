package com.jafpl.graph

import com.jafpl.runtime.{CompoundStep, DefaultCompoundEnd}

/**
  * Created by ndw on 10/2/16.
  */
class LoopEnd(graph: Graph, step: Option[CompoundStep]) extends DefaultCompoundEnd(graph, step) {
  label = Some("_loop_end")
}
