package com.jafpl.test

import com.jafpl.config.Jafpl
import com.jafpl.exceptions.JafplException
import com.jafpl.io.BufferConsumer
import com.jafpl.messages.{ItemMessage, Metadata}
import com.jafpl.primitive.PrimitiveRuntimeConfiguration
import com.jafpl.runtime.GraphRuntime
import com.jafpl.steps.{Manifold, ProduceBinding}
import org.scalatest.flatspec.AnyFlatSpec

class VariableBindingSpec extends AnyFlatSpec {
  var runtimeConfig = new PrimitiveRuntimeConfiguration()

  "A variable binding " should " work" in {
    val graph    = Jafpl.newInstance().newGraph()
    val pipeline = graph.addPipeline(Manifold.ALLOW_ANY)

    val bind     = pipeline.addVariable("fred", "some value")
    val prodbind = pipeline.addAtomic(new ProduceBinding("fred"), "pb")

    graph.addBindingEdge(bind, prodbind)
    graph.addEdge(prodbind, "result", pipeline, "result")

    graph.addOutput(pipeline, "result")

    graph.close()

    val runtime = new GraphRuntime(graph, runtimeConfig)
    val bc = new BufferConsumer()
    runtime.outputs("result").setConsumer(bc)
    runtime.runSync()

    assert(bc.items.size == 1)
    assert(bc.items.head == "some value")
  }

  /*
  "A variable binding provided by the runtime " should " also work" in {
    val graph    = Jafpl.newInstance().newGraph()
    val pipeline = graph.addPipeline(Manifold.ALLOW_ANY)

    val bind     = pipeline.addOption("fred", "some")
    val prodbind = pipeline.addAtomic(new ProduceBinding("fred"), "pb")

    graph.addBindingEdge(bind, prodbind)
    graph.addEdge(prodbind, "result", pipeline, "result")

    graph.addOutput(pipeline, "result")

    graph.close()

    val runtime = new GraphRuntime(graph, runtimeConfig)

    runtime.setOption("fred", "hello world")

    val bc = new BufferConsumer()
    runtime.outputs("result").setConsumer(bc)
    runtime.run()

    assert(bc.items.size == 1)
    assert(bc.items.head == "hello world")
  }
  */

  /*
  "A static variable binding " should " work" in {
    val graph    = Jafpl.newInstance().newGraph()
    val pipeline = graph.addPipeline(Manifold.ALLOW_ANY)

    val static   = new ItemMessage("static", Metadata.STRING)
    val bind     = pipeline.addStaticVariable("fred")
    val prodbind = pipeline.addAtomic(new ProduceBinding("fred"), "pb")

    graph.addBindingEdge(bind, prodbind)
    graph.addEdge(prodbind, "result", pipeline, "result")

    graph.addOutput(pipeline, "result")

    graph.close()

    val runtime = new GraphRuntime(graph, runtimeConfig)

    runtime.setStatic(bind, static)

    val bc = new BufferConsumer()
    runtime.outputs("result").setConsumer(bc)
    runtime.run()

    assert(bc.items.size == 1)
    assert(bc.items.head == "static")
  }

  "An unspecified static variable binding " should " fail" in {
    val graph    = Jafpl.newInstance().newGraph()
    val pipeline = graph.addPipeline(Manifold.ALLOW_ANY)

    val static   = new ItemMessage("static", Metadata.STRING)
    val bind     = pipeline.addStaticVariable("fred")
    val prodbind = pipeline.addAtomic(new ProduceBinding("fred"), "pb")

    graph.addBindingEdge(bind, prodbind)
    graph.addEdge(prodbind, "result", pipeline, "result")

    graph.addOutput(pipeline, "result")

    graph.close()

    val runtime = new GraphRuntime(graph, runtimeConfig)

    val bc = new BufferConsumer()
    runtime.outputs("result").setConsumer(bc)

    try {
      runtime.run()
      assert(false)
    } catch {
      case ex: JafplException =>
        if (ex.code != JafplException.UNDEFINED_STATIC) {
          throw ex
        }
        assert(true)
    }
  }
   */

  /*
  "An unreferenced unbound variable " should " be fine" in {
    val graph    = Jafpl.newInstance().newGraph()
    val pipeline = graph.addPipeline(Manifold.ALLOW_ANY)

    val bind_a   = pipeline.addOption("a", "")
    val bind_b   = pipeline.addOption("b", "")
    val prodbind = pipeline.addAtomic(new ProduceBinding("a"), "pb")

    graph.addBindingEdge(bind_a, prodbind)
    graph.addEdge(prodbind, "result", pipeline, "result")

    graph.addOutput(pipeline, "result")

    graph.close()

    val runtime = new GraphRuntime(graph, runtimeConfig)
    runtime.setOption("a", "0")
    val bc = new BufferConsumer()
    runtime.outputs("result").setConsumer(bc)

    var pass = true
    try {
      runtime.run()
    } catch {
      case t: Throwable =>
        println(t)
        pass = false
    }

    assert(pass)
  }

  "Intermediate variables " should " be computed" in {
    val graph    = Jafpl.newInstance().newGraph()
    val pipeline = graph.addPipeline(Manifold.ALLOW_ANY)

    val bind     = pipeline.addOption("a", "")

    val b        = pipeline.addVariable("b", "a + 1")
    val c        = pipeline.addVariable("c", "a + 2")
    val d        = pipeline.addVariable("d", "b + c")

    val prod     = pipeline.addAtomic(new ProduceBinding("d"), "pb")

    graph.addBindingEdge(bind, b)
    graph.addBindingEdge(bind, c)
    graph.addBindingEdge(b, d)
    graph.addBindingEdge(c, d)
    graph.addBindingEdge(d, prod)
    graph.addEdge(prod, "result", pipeline, "result")

    graph.addOutput(pipeline, "result")

    graph.close()

    val runtime = new GraphRuntime(graph, runtimeConfig)

    runtime.setOption("a", "1")

    val bc = new BufferConsumer()
    runtime.outputs("result").setConsumer(bc)
    runtime.run()

    assert(bc.items.size == 1)
    assert(bc.items.head == 5)
  }
   */
}
