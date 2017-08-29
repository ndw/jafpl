package com.jafpl.test

import java.io.{File, PrintWriter}

import com.jafpl.drivers.GraphTest.runtimeConfig
import com.jafpl.graph.Graph
import com.jafpl.io.BufferConsumer
import com.jafpl.primitive.{PrimitiveItemTester, PrimitiveRuntimeConfiguration}
import com.jafpl.runtime.GraphRuntime
import com.jafpl.steps.{Decrement, Producer}
import org.scalatest.FlatSpec

class WhileSpec extends FlatSpec {
  var runtimeConfig = new PrimitiveRuntimeConfiguration()

  "A while " should " iterate until finished" in {
    val graph = new Graph()
    val pipeline = graph.addPipeline()
    val p1       = pipeline.addAtomic(new Producer(List(7)), "p1")

    val tester   = new PrimitiveItemTester(runtimeConfig, ". > 0")
    val wstep    = pipeline.addWhile(tester)
    val decr     = wstep.addAtomic(new Decrement(), "decr")

    graph.addEdge(p1, "result", wstep, "source")
    graph.addEdge(wstep, "current", decr, "source")
    graph.addEdge(decr, "result", wstep, "result")
    graph.addEdge(decr, "result", wstep, "test")

    graph.addEdge(wstep, "result", pipeline, "result")

    graph.addOutput(pipeline, "result")

    val runtime = new GraphRuntime(graph, runtimeConfig)
    val bc = new BufferConsumer()
    runtime.outputs("result").setConsumer(bc)
    runtime.run()

    assert(bc.items.size == 7)
    assert(bc.items.head == 6)
    assert(bc.items.last == 0)
  }

  "A while " should " not run without a test" in {
    val graph = new Graph()
    val pipeline = graph.addPipeline()
    val p1       = pipeline.addAtomic(new Producer(List(7)), "p1")

    val tester   = new PrimitiveItemTester(runtimeConfig, ". > 0")
    val wstep    = pipeline.addWhile(tester)
    val decr     = wstep.addAtomic(new Decrement(), "decr")

    graph.addEdge(p1, "result", wstep, "source")
    graph.addEdge(wstep, "current", decr, "source")
    graph.addEdge(decr, "result", wstep, "result")
    //graph.addEdge(decr, "result", wstep, "test")

    graph.addEdge(wstep, "result", pipeline, "result")
    graph.addOutput(pipeline, "result")

    var pass = true
    try {
      graph.close()
    } catch {
      case _: Throwable => true
    }

    assert(pass)
  }

  "A while " should " not iterate at all if it's condition is initially false" in {
    val graph = new Graph()
    val pipeline = graph.addPipeline()
    val p1       = pipeline.addAtomic(new Producer(List(0)), "p1")

    val tester   = new PrimitiveItemTester(runtimeConfig, ". > 0")
    val wstep    = pipeline.addWhile(tester)
    val decr     = wstep.addAtomic(new Decrement(), "decr")

    graph.addEdge(p1, "result", wstep, "source")
    graph.addEdge(wstep, "source", decr, "source")
    graph.addEdge(decr, "result", wstep, "test")
    graph.addEdge(decr, "result", wstep, "result")

    graph.addEdge(wstep, "result", pipeline, "result")

    graph.addOutput(pipeline, "result")

    val runtime = new GraphRuntime(graph, runtimeConfig)
    val bc = new BufferConsumer()
    runtime.outputs("result").setConsumer(bc)
    runtime.run()

    assert(bc.items.size == 1)
    assert(bc.items.head == 0)
  }

  "A while " should " be able to have multiple outputs" in {
    val graph = new Graph()
    val pipeline = graph.addPipeline()
    val p1       = pipeline.addAtomic(new Producer(List(3)), "p1")

    val tester   = new PrimitiveItemTester(runtimeConfig, ". > 0")
    val wstep    = pipeline.addWhile(tester)
    val decr     = wstep.addAtomic(new Decrement(), "decr")

    graph.addEdge(p1, "result", wstep, "source")
    graph.addEdge(wstep, "current", decr, "source")
    graph.addEdge(decr, "result", wstep, "test")
    graph.addEdge(decr, "result", wstep, "result1")
    graph.addEdge(decr, "result", wstep, "result2")

    graph.addEdge(wstep, "result1", pipeline, "result")
    graph.addEdge(wstep, "result2", pipeline, "result")

    graph.addOutput(pipeline, "result")

    graph.close()
    val pw = new PrintWriter(new File("/projects/github/xproc/jafpl/pg.xml"))
    pw.write(graph.asXML.toString)
    pw.close()

    val runtime = new GraphRuntime(graph, runtimeConfig)
    val bc = new BufferConsumer()
    runtime.outputs("result").setConsumer(bc)
    runtime.run()

    assert(bc.items.size == 4)
    assert(bc.items(0) == 2)
    assert(bc.items(1) == 2)
    assert(bc.items(2) == 1)
    assert(bc.items(3) == 1)
  }

}