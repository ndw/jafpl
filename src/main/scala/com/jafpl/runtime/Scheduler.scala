package com.jafpl.runtime

import com.jafpl.exceptions.JafplExceptionCode
import com.jafpl.graph._
import com.jafpl.messages.Message
import com.jafpl.runtime.NodeState.NodeState
import com.jafpl.util.TraceEventManager

import scala.collection.mutable.ListBuffer

class Scheduler(val runtime: GraphRuntime) extends Runnable {
  private val CYCLETIME = 1
  private val WATCHDOGLIMIT = 5
  private val graphStatus = new GraphStatus(this)
  private val pool = new ThreadPool(this,runtime.runtime.threadPoolSize)
  private val tracer = runtime.traceEventManager
  private var _exception = Option.empty[Throwable]
  private var done = false

  def exception: Option[Throwable] = _exception

  def startNode(node: Node): Unit = {
    graphStatus.startNode(node)
  }

  def reset(node: Node, state: NodeState): Unit = {
    graphStatus.reset(node, state)
  }

  def abort(node: Node): Unit = {
    graphStatus.abort(node)
  }

  def runnable(node: Node): Unit = {
    graphStatus.runnable(node)
  }

  def run(): Unit = {
    tracer.trace("RUN   SCHEDULER", TraceEventManager.SCHEDULER)

    var nodeToRun = Option.empty[Node]
    done = false
    var watchdog = 0
    while (!done) {
      if (_exception.isDefined) {
        tracer.trace("ABORT SCHEDULER: " + _exception.get, TraceEventManager.SCHEDULER)
        graphStatus.abort()
        done = true
      } else {
        tracer.trace("CHECK SCHEDULER", TraceEventManager.SCHEDULER)
        done = pool.idle && graphStatus.finished()
        if (!done) {
          nodeToRun = graphStatus.findReady()
        }
      }

      if (!done) {
        if (nodeToRun.isEmpty) {
          if (pool.idle) {
            watchdog += 1

            if (watchdog > WATCHDOGLIMIT) {
              _exception = Some(new RuntimeException("Looping without runnable actions"))
            }

            tracer.trace("debug", "Scheduler has no runnable actions", TraceEventManager.SCHEDULER)
          }
          Thread.sleep(CYCLETIME)
        } else {
          watchdog = 0
          start(nodeToRun.get)
          nodeToRun = None
        }
      }
    }

    pool.joinAll()
    graphStatus.stop()
    tracer.trace("DONE  SCHEDULER", TraceEventManager.SCHEDULER)
    if (_exception.isDefined) {
      runtime.finish(_exception.get)
    } else {
      runtime.finish()
    }
  }

  def stop(): Unit = {
    done = true
    graphStatus.stop()
  }

  def stop(node: Node): Unit = {
    graphStatus.stop(node)
  }

  private def start(node: Node): Unit = {
    try {
      if (_exception.isEmpty) {
        _exception = graphStatus.checkInputCardinalities(node)
      }
      if (_exception.isDefined) {
        return
      }

      node match {
        case choose: ChooseStart =>
          var theOne = Option.empty[WhenStart]
          for (child <- choose.children) {
            child match {
              case when: WhenStart =>
                val pass = theOne.isEmpty && graphStatus.testWhen(when)
                if (theOne.isEmpty && pass) {
                  theOne = Some(when)
                  graphStatus.runnable(when)
                } else {
                  graphStatus.finished(when)
                  graphStatus.finished(when.containerEnd)
                }
              case _ => ()
            }
          }
        case _ => ()
      }
    } catch {
      case t: Throwable =>
        reportException(node, t)
        return
    }

    tracer.trace("SCHED  " + node, TraceEventManager.SCHEDULER)
    val proxy = new ActionProxy(graphStatus, node)
    var thread = pool.getThread(proxy)
    while (thread.isEmpty) {
      Thread.sleep(100)
      thread = pool.getThread(proxy)
    }

    graphStatus.unbuffer(node)
    graphStatus.running(node)
    thread.get.start()
  }

  def finish(node: Node): Unit = {
    synchronized {
      if (_exception.isEmpty) {
        _exception = graphStatus.checkOutputCardinalities(node)
      }
      if (_exception.isDefined) {
        return
      }

      tracer.trace("FINSH " + node, TraceEventManager.RUN)
      var loopStart = Option.empty[LoopStart]
      node match {
        case end: ContainerEnd =>
          val start = end.start.get
          start match {
            case loop: LoopStart =>
              tracer.trace("LOOP: " + loop.iterationPosition + " of " + loop.iterationSize, TraceEventManager.LOOP)
              if (!graphStatus.loopFinished(loop)) {
                loopStart = Some(loop)
              }
            case _: TryStart =>
              // The try ended normally, so all of the catches should be released and the finally can run
              val trycatch = start.parent.get
              for (child <- trycatch.children) {
                child match {
                  case cs: CatchStart =>
                    graphStatus.finished(cs)
                    graphStatus.finished(cs.containerEnd)
                  case fs: FinallyStart =>
                    graphStatus.runnable(fs)
                  case _ => ()
                }
              }
            case finstep: FinallyStart =>
              if (finstep.percolate) {
                reportException(finstep.parent.get.parent.get, finstep.cause.get)
              }
            case katch: CatchStart =>
              val finstep = katch.parent.get.children.filter(_.isInstanceOf[FinallyStart])
              if (finstep.nonEmpty) {
                finstep.head.asInstanceOf[FinallyStart].percolate = false
                graphStatus.runnable(finstep.head)
              }
            case _ => ()
          }
        case _ => ()
      }

      if (loopStart.isEmpty) {
        graphStatus.finished(node)
      } else {
        graphStatus.loop(loopStart.get)
      }
    }
  }

  def reportException(node: Node, cause: Throwable): Unit = {
    node match {
      case atry: TryStart =>
        abort(atry)

        var code: Option[Any] = None
        cause match {
          case je: JafplExceptionCode =>
            code = Some(je.jafplExceptionCode)
            tracer.trace(s"EXCPT Code=${code.get}; ${cause.getMessage}", TraceEventManager.EXCEPTIONS)
          case _ =>
            tracer.trace(s"EXCPT ${cause.getMessage}", TraceEventManager.EXCEPTIONS)
        }

        selectCatch(atry.parent.get.asInstanceOf[TryCatchStart], cause, code)
      case _ =>
        if (node.parent.isDefined) {
          reportException(node.parent.get, cause)
        } else {
          _exception = Some(cause)
        }
    }
  }

  private def selectCatch(node: TryCatchStart, cause: Throwable, code: Option[Any]): Unit = {
    var useCatch = Option.empty[CatchStart]
    val catchList = ListBuffer.empty[CatchStart]
    for (child <- node.children.filter(_.isInstanceOf[CatchStart])) {
      catchList += child.asInstanceOf[CatchStart]
    }

    // If there's a finally, remember the cause
    var theFinally = Option.empty[FinallyStart]
    for (child <- node.children.filter(_.isInstanceOf[FinallyStart])) {
      theFinally = Some(child.asInstanceOf[FinallyStart])
    }
   if (theFinally.isDefined) {
      theFinally.get.cause = Some(cause)
    }

    for (catchBlock <- catchList) {
      if (useCatch.isEmpty) {
        var codeMatches = catchBlock.codes.isEmpty
        if (code.isDefined) {
          for (catchCode <- catchBlock.codes) {
            codeMatches = codeMatches || (code.get == catchCode)
          }
        }
        if (codeMatches) {
          useCatch = Some(catchBlock)
        }
      }
    }

    if (useCatch.isDefined) {
      for (acatch <- catchList) {
        if (acatch == useCatch.get) {
          tracer.trace(s"CATCH $acatch marked runnable", TraceEventManager.EXCEPTIONS)
          acatch.cause = Some(cause)
          graphStatus.runnable(acatch)
        } else {
          tracer.trace(s"CATCH $acatch marked finished", TraceEventManager.EXCEPTIONS)
          graphStatus.finished(acatch)
          graphStatus.finished(acatch.containerEnd)
        }
      }
    } else {
      tracer.trace(s"CATCH No matching catch, exception percolates up", TraceEventManager.EXCEPTIONS)
      if (theFinally.isDefined) {
        theFinally.get.percolate = true
        graphStatus.runnable(theFinally.get)
      } else {
        reportException(node, cause)
      }
    }
  }

  def receive(action: Action, port: String, message: Message): Unit = {
    receive(action.node, port, message)
  }

  def receive(node: Node, port: String, message: Message): Unit = {
    if (node.hasOutputEdge(port)) {
      val edge = node.outputEdge(port)
      if (_exception.isEmpty) {
        _exception = graphStatus.checkCardinalities(edge)
        if (_exception.isEmpty) {
          graphStatus.receive(edge.from, edge.fromPort, edge.to, edge.toPort, message)
        }
      }
    } else {
      // Ignore this
      tracer.trace("debug", s"$node attempted to write to non-existant port $port", TraceEventManager.IO)
    }
  }

  def receiveOutput(port: String, message: Message): Unit = {
    val proxy = runtime.outputs.get(port)
    if (proxy.isDefined) {
      proxy.get.consume(proxy.get.outputPort, message)
    }
  }

  class ActionProxy(graphStatus: GraphStatus, node: Node) extends Runnable {
    def run(): Unit = {
      graphStatus.run(node)
    }

    override def toString: String = {
      node.toString
    }
  }
}
