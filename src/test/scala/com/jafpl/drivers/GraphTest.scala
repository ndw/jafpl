package com.jafpl.drivers

import java.io.{File, PrintWriter}

import com.jafpl.graph.Graph
import com.jafpl.io.BufferConsumer
import com.jafpl.primitive.{PrimitiveItemComparator, PrimitiveItemTester, PrimitiveRuntimeConfiguration}
import com.jafpl.runtime.GraphRuntime
import com.jafpl.steps.{Decrement, Producer}

object GraphTest extends App {
  var runtimeConfig = new PrimitiveRuntimeConfiguration()

  //val pw = new PrintWriter(new File("/projects/github/xproc/jafpl/pg.xml"))
  //pw.write(graph.asXML.toString)
  //pw.close()

  runFour()

  def runFour(): Unit = {
    val graph = new Graph()
    val pipeline = graph.addPipeline()
    val p1       = pipeline.addAtomic(new Producer(List("Hello")), "p1")

    graph.close()
    val pw = new PrintWriter(new File("/projects/github/xproc/jafpl/pg.xml"))
    pw.write(graph.asXML.toString)
    pw.close()

    val runtime = new GraphRuntime(graph, runtimeConfig)
    runtime.run()
  }

  def runThree(): Unit = {
    val graph = new Graph()
    val pipeline = graph.addPipeline()
    val p1       = pipeline.addAtomic(new Producer(List(0)), "p1")

    val tester   = new PrimitiveItemTester(runtimeConfig, ". > 0")
    val wstep    = pipeline.addWhile(tester)
    val decr     = wstep.addAtomic(new Decrement(), "decr")

    graph.addEdge(p1, "result", wstep, "source")
    graph.addEdge(wstep, "source", decr, "source")
    graph.addEdge(decr, "result", wstep, "result")

    graph.addEdge(wstep, "result", pipeline, "result")

    graph.addOutput(pipeline, "result")

    graph.close()
    val pw = new PrintWriter(new File("/projects/github/xproc/jafpl/pg.xml"))
    pw.write(graph.asXML.toString)
    pw.close()

    val runtime = new GraphRuntime(graph, runtimeConfig)
    val bc = new BufferConsumer()
    runtime.outputs("result").setProvider(bc)
    runtime.run()

    println(bc.items.size)
    println(bc.items.head)
  }

  def runTwo(): Unit = {
    val graph = new Graph()
    val pipeline = graph.addPipeline()
    val p1       = pipeline.addAtomic(new Producer(List(7)), "p1")


    val comp = new PrimitiveItemComparator()

    val ustep    = pipeline.addUntil(comp)
    val decr     = ustep.addAtomic(new Decrement(), "decr")

    graph.addEdge(p1, "result", ustep, "source")
    graph.addEdge(ustep, "source", decr, "source")
    graph.addEdge(decr, "result", ustep, "result")

    graph.addEdge(ustep, "result", pipeline, "result")

    graph.addOutput(pipeline, "result")

    graph.close()
    val pw = new PrintWriter(new File("/projects/github/xproc/jafpl/pg.xml"))
    pw.write(graph.asXML.toString)
    pw.close()

    val runtime = new GraphRuntime(graph, runtimeConfig)
    val bc = new BufferConsumer()
    runtime.outputs("result").setProvider(bc)
    runtime.run()

    println(bc.items.size)
    println(bc.items.head)
  }

  def runOne(): Unit = {
    val graph = new Graph()
    val pipeline = graph.addPipeline()
    val p1       = pipeline.addAtomic(new Producer(List(7)), "p1")

    val tester   = new PrimitiveItemTester(runtimeConfig, ". > 0")
    val wstep    = pipeline.addWhile(tester)
    val decr     = wstep.addAtomic(new Decrement(), "decr")

    graph.addEdge(p1, "result", wstep, "source")
    graph.addEdge(wstep, "source", decr, "source")
    graph.addEdge(decr, "result", wstep, "result")

    graph.addEdge(wstep, "result", pipeline, "result")

    graph.addOutput(pipeline, "result")

    graph.close()
    val pw = new PrintWriter(new File("/projects/github/xproc/jafpl/pg.xml"))
    pw.write(graph.asXML.toString)
    pw.close()

    val runtime = new GraphRuntime(graph, runtimeConfig)
    val bc = new BufferConsumer()
    runtime.outputs("result").setProvider(bc)
    runtime.run()

    println(bc.items.size)
    println(bc.items.head)
  }
}
