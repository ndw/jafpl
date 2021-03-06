package com.jafpl.test

import com.jafpl.config.Jafpl
import com.jafpl.io.BufferConsumer
import com.jafpl.messages.{ItemMessage, Metadata}
import com.jafpl.primitive.PrimitiveRuntimeConfiguration
import com.jafpl.runtime.GraphRuntime
import com.jafpl.steps.{Identity, Manifold}
import org.scalatest.flatspec.AnyFlatSpec

class ProvidersSpec extends AnyFlatSpec {
  val PIPELINEDATA = "Document"
  var runtimeConfig = new PrimitiveRuntimeConfiguration(false)

  "Pipeline providers " should " should provide input and consume output" in {
    val graph = Jafpl.newInstance().newGraph()
    val bc = new BufferConsumer()

    val pipeline = graph.addPipeline(None, Manifold.ALLOW_ANY)
    val ident = pipeline.addAtomic(new Identity(), "ident")

    graph.addEdge(pipeline, "source", ident, "source")
    graph.addEdge(ident, "result", pipeline, "result")

    graph.addInput(pipeline, "source")
    graph.addOutput(pipeline, "result")

    graph.close()

    val runtime = new GraphRuntime(graph, runtimeConfig)
    runtime.inputs("source").send(new ItemMessage(PIPELINEDATA, Metadata.BLANK))
    runtime.outputs("result").setConsumer(bc)
    runtime.runSync()

    assert(bc.items.size == 1)
    assert(bc.items.head == PIPELINEDATA)
  }
}