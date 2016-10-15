package com.jafpl.graph

import akka.actor.{ActorRef, ActorSystem, Props}
import com.jafpl.messages.{CloseMessage, StartMessage}
import com.jafpl.util.XmlWriter
import com.jafpl.runtime._
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.collection.immutable

/**
  * Created by ndw on 10/2/16.
  */
class Graph() {
  protected val logger = LoggerFactory.getLogger(this.getClass)
  private val nodes = mutable.HashSet.empty[Node]
  private val fans = mutable.HashMap.empty[Node, Node]
  private val edges = mutable.HashSet.empty[Edge]
  private var _validated = false
  private var _valid = true
  private var _finished = false
  private var _system: ActorSystem = _
  private var _monitor: ActorRef = _

  private[graph] def finished = _finished
  private[graph] def system = _system
  private[graph] def monitor = _monitor

  def chkValid() = {
    if (_validated) {
      throw new GraphException("Attempt to change validated graph")
    }
  }

  def createNode(): Node = {
    createNode(None)
  }

  def createNode(name: String): Node = {
    createNode(Some(name))
  }

  private def createNode(name: Option[String]): Node = {
    chkValid()
    val node = new Node(this, None)
    node.label = name
    nodes.add(node)
    node
  }

  def createNode(step: Step): Node = {
    chkValid()
    val node = new Node(this, Some(step))
    nodes.add(node)
    node
  }

  def createInputNode(name: String): InputNode = {
    chkValid()
    val node = new InputNode(this, name)
    nodes.add(node)
    node
  }

  def createOutputNode(name: String): OutputNode = {
    chkValid()
    val node = new OutputNode(this, name)
    nodes.add(node)
    node
  }

  def createVariableNode(step: Step): Node = {
    chkValid()
    val node = new Node(this, Some(step))
    nodes.add(node)
    node
  }

  def createIteratorNode(subpipeline: List[Node]): LoopStart = {
    createIteratorNode(None, subpipeline)
  }

  def createIteratorNode(step: CompoundStep, subpipeline: List[Node]): LoopStart = {
    createIteratorNode(Some(step), subpipeline)
  }

  private def createIteratorNode(step: Option[CompoundStep], subpipeline: List[Node]): LoopStart = {
    chkValid()
    val loopStart = new LoopStart(this, step, subpipeline)
    val loopEnd   = new LoopEnd(this, step)

    loopStart.endNode = loopEnd
    loopEnd.startNode = loopStart

    nodes.add(loopStart)
    nodes.add(loopEnd)

    loopStart
  }

  private[graph] def createIterationCacheNode(): IterationCache = {
    val node = new IterationCache(this)
    nodes.add(node)
    node
  }

  def createChooseNode(subpipeline: List[Node]): ChooseStart = {
    createChooseNode(Some(new Chooser()), subpipeline)
  }

  private def createChooseNode(step: Option[CompoundStep], subpipeline: List[Node]): ChooseStart = {
    chkValid()
    val chooseStart = new ChooseStart(this, step, subpipeline)
    val chooseEnd   = new ChooseEnd(this, step)

    chooseStart.endNode = chooseEnd
    chooseEnd.startNode = chooseStart

    nodes.add(chooseStart)
    nodes.add(chooseEnd)

    chooseStart
  }

  def createWhenNode(subpipeline: List[Node]): WhenStart = {
    createWhenNode(Some(new WhenTrue()), subpipeline)
  }

  def createWhenNode(step: WhenStep, subpipeline: List[Node]): WhenStart = {
    createWhenNode(Some(step), subpipeline)
  }

  private def createWhenNode(step: Option[WhenStep], subpipeline: List[Node]): WhenStart = {
    chkValid()

    val whenStep = if (step.isDefined) {
      Some(new Whener(step.get))
    } else {
      step
    }

    val whenStart = new WhenStart(this, whenStep, subpipeline)
    val whenEnd   = new WhenEnd(this, whenStep)

    whenStart.endNode = whenEnd
    whenEnd.startNode = whenStart

    nodes.add(whenStart)
    nodes.add(whenEnd)

    whenStart
  }

  def addEdge(from: Port, to: Port): Unit = {
    chkValid()
    addEdge(from.node, from.name, to.node, to.name)
  }

  def addEdge(source: Node, outputPort: String, destination: Node, inputPort: String): Unit = {
    chkValid()

    //logger.info("addEdge: " + source + "." + outputPort + " -> " + destination + "." + inputPort)

    val from =
      if (source.output(outputPort).isDefined) {
        val edge = source.output(outputPort).get

        if (fans.contains(edge.source)) {
          val fanOut = fans(edge.source).asInstanceOf[FanOut]
          fanOut.nextPort
        } else {
          val fanOut = new FanOut(this)
          nodes.add(fanOut)
          fans.put(edge.source, fanOut)

          removeEdge(edge)
          addEdge(source, outputPort, fanOut, "source")

          val targetPort = new Port(edge.destination, edge.inputPort)
          addEdge(fanOut.nextPort, targetPort)
          fanOut.nextPort
        }
    } else {
        new Port(source, outputPort)
      }
    val to =
      if (destination.input(inputPort).isDefined) {
        val edge = destination.input(inputPort).get

        if (fans.contains(edge.destination)) {
          val fanIn = fans(edge.destination).asInstanceOf[FanIn]
          fanIn.nextPort
        } else {
          val fanIn = new FanIn(this)
          nodes.add(fanIn)
          fans.put(edge.destination, fanIn)
          edge.source.removeOutput(edge.outputPort)
          edge.destination.removeInput(edge.inputPort)
          edges.remove(edge)
          addEdge(fanIn, "result", destination, inputPort)
          val sourcePort = new Port(edge.source, edge.outputPort)
          addEdge(sourcePort, fanIn.nextPort)
          fanIn.nextPort
        }
      } else {
        new Port(destination, inputPort)
      }

    val edge = new Edge(this, from, to)

    //logger.info("         " + from + " -> " + to)

    edges.add(edge)
  }

  private[graph] def removeEdge(edge: Edge): Unit = {
    //logger.info("rmvEdge: " + edge.source + "." + edge.outputPort + " -> " + edge.destination + "." + edge.inputPort)

    edge.source.removeOutput(edge.outputPort)
    edge.destination.removeInput(edge.inputPort)
    edges.remove(edge)
  }

  def addDependency(node: Node, dependsOn: Node): Unit = {
    chkValid()
    node.addDependancy(dependsOn)
  }

  private[graph] def finish(): Unit = {
    _finished = true
  }

  def valid(): Boolean = {
    if (_validated) {
      return _valid
    }

    val srcPorts = mutable.HashSet.empty[Port]
    val dstPorts = mutable.HashSet.empty[Port]
    for (edge <- edges) {
      val src = new Port(edge.source, edge.outputPort)
      val dst = new Port(edge.destination, edge.inputPort)

      srcPorts += src
      dstPorts += dst

      if (srcPorts.contains(dst)) {
        _valid = false
        throw new GraphException("Attempt to write to an input port: " + dst)
      }

      if (dstPorts.contains(src)) {
        _valid = false
        throw new GraphException("Attempt to read to an output port: " + dst)
      }
    }

    for (node <- nodes) {
      _valid = _valid && node.valid
      _valid = _valid && node.noCycles(immutable.HashSet.empty[Node])
      _valid = _valid && node.connected()
    }

    if (_valid) {
      for (node <- nodes) {
        node.addIterationCaches()
        node.addWhenCaches()
        node.addChooseCaches()
      }
    }

    _validated = true
    _valid
  }

  private def roots(): Set[Node] = {
    val roots = mutable.HashSet.empty[Node]
    for (node <- nodes) {
      if (node.inputs().isEmpty) {
        roots.add(node)
      }
    }
    roots.toSet
  }

  private[graph] def inputs(): List[InputNode] = {
    val inodes = mutable.ListBuffer.empty[InputNode]
    for (node <- nodes) {
      node match {
        case n: InputNode => inodes += n
        case _ => Unit
      }
    }
    inodes.toList
  }

  private [graph] def options(): List[InputOption] = {
    val onodes = mutable.ListBuffer.empty[InputOption]
    for (node <- nodes) {
      node match {
        case n: InputOption => onodes += n
        case _ => Unit
      }
    }
    onodes.toList
  }

  private[graph] def outputs(): List[OutputNode] = {
    val onodes = mutable.ListBuffer.empty[OutputNode]
    for (node <- nodes) {
      node match {
        case n: OutputNode => onodes += n
        case _ => Unit
      }
    }
    onodes.toList
  }

  private[graph] def makeActors(): Unit = {
    _system = ActorSystem("jafpl-com")
    _monitor = _system.actorOf(Props(new GraphMonitor(this)), name="monitor")

    for (node <- nodes) {
      node.makeActors()
    }

    // Run all the roots that aren't InputNodes or OutputNodes
    for (root <- roots()) {
      root match {
        case node: InputNode => Unit
        case node: OutputNode => Unit
        case node: Node =>
          node.actor ! new StartMessage(node.actor)
      }
    }
  }

  def dump(): String = {
    val tree = new XmlWriter()
    tree.startDocument()
    tree.addStartElement(Serializer.pg_graph)
    for (node <- nodes) {
      node.dump(tree)
    }
    tree.addEndElement()
    tree.endDocument()
    tree.getResult
  }
}
