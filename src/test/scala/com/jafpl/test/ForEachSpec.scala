package com.jafpl.test

import com.jafpl.config.Jafpl
import com.jafpl.primitive.PrimitiveRuntimeConfiguration
import com.jafpl.runtime.GraphRuntime
import com.jafpl.steps.{BufferSink, Count, Identity, Manifold, ProduceBinding, Producer, Sink}
import org.scalatest.flatspec.AnyFlatSpec

class ForEachSpec extends AnyFlatSpec {
  var runtimeConfig = new PrimitiveRuntimeConfiguration()

  "A for-each " should " iterate" in {
    val graph    = Jafpl.newInstance().newGraph()

    val pipeline = graph.addPipeline(Manifold.ALLOW_ANY)
    val producer = pipeline.addAtomic(new Producer(List(1, 2, 3)), "producer")
    val forEach  = pipeline.addForEach("for-each", Manifold.ALLOW_ANY)
    val ident    = forEach.addAtomic(new Identity(), "ident")

    val bc = new BufferSink()

    graph.addEdge(producer, "result", forEach, "source")
    graph.addEdge(forEach, "current", ident, "source")
    graph.addEdge(ident, "result", forEach, "result")
    graph.addEdge(forEach, "result", pipeline, "result")
    graph.addOutput(pipeline, "result")

    val runtime = new GraphRuntime(graph, runtimeConfig)
    runtime.outputs("result").setConsumer(bc)
    runtime.runSync()

    var count = 1
    for (buf <- bc.items) {
      assert(buf.toString == count.toString)
      count += 1
    }
    assert(count == 4)
  }

  "A for-each with three inputs " should " output 3 documents" in {
    val graph    = Jafpl.newInstance().newGraph()

    val pipeline = graph.addPipeline(Manifold.ALLOW_ANY)
    val producer = pipeline.addAtomic(new Producer(List("1", "2", "3")), "producer")
    val forEach  = pipeline.addForEach("for-each", Manifold.ALLOW_ANY)
    val ident    = forEach.addAtomic(new Identity(), "ident")

    val count    = pipeline.addAtomic(new Count(), "count")

    val bc = new BufferSink()

    graph.addEdge(producer, "result", forEach, "source")
    graph.addEdge(forEach, "current", ident, "source")
    graph.addEdge(ident, "result", forEach, "result")
    graph.addEdge(forEach, "result", count, "source")
    graph.addEdge(count, "result", pipeline, "result")
    graph.addOutput(pipeline, "result")

    val runtime = new GraphRuntime(graph, runtimeConfig)
    runtime.outputs("result").setConsumer(bc)
    runtime.runSync()

    assert(bc.items.size == 1)
    assert(bc.items.head == 3)
  }

  "Inputs that cross a for-each " should " be buffered" in {
    val graph    = Jafpl.newInstance().newGraph()

    val pipeline = graph.addPipeline(Manifold.ALLOW_ANY)
    val cprod    = pipeline.addAtomic(new Producer(List("1", "2", "3", "4")), "count_producer")
    val lprod    = pipeline.addAtomic(new Producer(List("1", "2", "3")), "loop_producer")
    val count    = pipeline.addAtomic(new Count(), "count")
    val forEach  = pipeline.addForEach("for-each", Manifold.ALLOW_ANY)
    val sink     = forEach.addAtomic(new Sink(), "sink")
    val ident    = forEach.addAtomic(new Identity(), "ident")

    val bc = new BufferSink()

    graph.addEdge(cprod, "result", count, "source")
    graph.addEdge(count, "result", ident, "source")

    graph.addEdge(lprod, "result", forEach, "source")
    graph.addEdge(forEach, "current", sink, "source")
    graph.addEdge(ident, "result", forEach, "result")
    graph.addEdge(forEach, "result", pipeline, "result")
    graph.addOutput(pipeline, "result")

    val runtime = new GraphRuntime(graph, runtimeConfig)
    runtime.outputs("result").setConsumer(bc)
    runtime.runSync()

    assert(bc.items.size == 3)
    for (item <- bc.items) {
      assert(item == 4)
    }
  }

  "Buffers " should " reset correctly with nested loops" in {
    val graph    = Jafpl.newInstance().newGraph()

    val pipeline = graph.addPipeline(Manifold.ALLOW_ANY)
    val outerlprod = pipeline.addAtomic(new Producer(List("1", "2", "3")), "outer_loop_producer")
    val outerForEach = pipeline.addForEach("outer-for-each", Manifold.ALLOW_ANY)

    val outerSink = outerForEach.addAtomic(new Sink(), "outer-sink")
    val cprod    = outerForEach.addAtomic(new Producer(List("1", "2", "3", "4")), "count_producer")
    val lprod    = outerForEach.addAtomic(new Producer(List("1", "2", "3")), "loop_producer")
    val count    = outerForEach.addAtomic(new Count(), "count")
    val forEach  = outerForEach.addForEach("for-each", Manifold.ALLOW_ANY)
    val sink     = forEach.addAtomic(new Sink(), "inner-sink")
    val ident    = forEach.addAtomic(new Identity(), "ident")

    val bc = new BufferSink()

    graph.addEdge(outerlprod, "result", outerForEach, "source")
    graph.addEdge(outerForEach, "current", outerSink, "source")

    graph.addEdge(cprod, "result", count, "source")
    graph.addEdge(count, "result", ident, "source")

    graph.addEdge(lprod, "result", forEach, "source")
    graph.addEdge(forEach, "current", sink, "source")
    graph.addEdge(ident, "result", forEach, "result")
    graph.addEdge(forEach, "result", outerForEach, "result")
    graph.addEdge(outerForEach, "result", pipeline, "result")
    graph.addOutput(pipeline, "result")

    val runtime = new GraphRuntime(graph, runtimeConfig)
    runtime.outputs("result").setConsumer(bc)
    runtime.runSync()

    assert(bc.items.size == 9)
    for (item <- bc.items) {
      assert(item == 4)
    }
  }

  "Varibles that cross a for-each " should " be buffered" in {
    val graph    = Jafpl.newInstance().newGraph()

    val pipeline = graph.addPipeline(Manifold.ALLOW_ANY)

    val bind     = pipeline.addVariable("fred", "some value")
    val lprod    = pipeline.addAtomic(new Producer(List("1", "2", "3")), "loop_producer")
    val forEach  = pipeline.addForEach("for-each", Manifold.ALLOW_ANY)
    val sink     = forEach.addAtomic(new Sink(), "sink")
    val prodbind = forEach.addAtomic(new ProduceBinding("fred"), "pb")

    val bc = new BufferSink()

    graph.addBindingEdge(bind, prodbind)

    graph.addEdge(lprod, "result", forEach, "source")
    graph.addEdge(forEach, "current", sink, "source")
    graph.addEdge(prodbind, "result", forEach, "result")
    graph.addEdge(forEach, "result", pipeline, "result")
    graph.addOutput(pipeline, "result")

    val runtime = new GraphRuntime(graph, runtimeConfig)

    runtime.outputs("result").setConsumer(bc)

    runtime.runSync()

    assert(bc.items.size == 3)
    for (item <- bc.items) {
      assert(item == "some value")
    }
  }

  "A for-each with no input " should " produce no output" in {
    val graph    = Jafpl.newInstance().newGraph()

    val pipeline = graph.addPipeline(Manifold.ALLOW_ANY)
    val producer = pipeline.addAtomic(new Producer(List()), "producer")
    val forEach  = pipeline.addForEach("for-each", Manifold.ALLOW_ANY)
    val ident    = forEach.addAtomic(new Identity(), "ident")

    val bc = new BufferSink()

    graph.addEdge(producer, "result", forEach, "source")
    graph.addEdge(forEach, "source", ident, "source")
    graph.addEdge(ident, "result", forEach, "result")
    graph.addEdge(forEach, "result", pipeline, "result")
    graph.addOutput(pipeline, "result")

    val runtime = new GraphRuntime(graph, runtimeConfig)

    runtime.outputs("result").setConsumer(bc)

    runtime.runSync()

    assert(bc.items.isEmpty)
  }
}