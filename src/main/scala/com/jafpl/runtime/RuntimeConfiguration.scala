package com.jafpl.runtime

/** The runtime configuration for pipeline execution.
  *
  * This object provides configuration information to the pipeline execution engine.
  *
  */
trait RuntimeConfiguration {
  /** Returns the expression evaluator.
    *
    * An [[com.jafpl.runtime.ExpressionEvaluator]] is required to evaluate expressions
    * for variable bindings and the when branches of a choose.
    *
    * @return The evaluator.
    */
  def expressionEvaluator(): ExpressionEvaluator

  /** Enable trace events.
    *
    * The actors that run steps will emit log messages if the appropriate traces are enabled.
    * This method is called to determine if a particular trace is enabled.
    *
    * @param trace A trace event.
    * @return True, if that event should be considered enabled.
    */
  def traceEnabled(trace: String): Boolean

  /** The watchdog timeout.
    *
    * Pipelines are a message passing, data flow system. Progress only occurs when steps are producing and
    * consuming items. If no messages are sent for a period of `watchdogTimeout` milliseconds, the
    * pipeline will be treated as "hung" and terminated.
    *
    * In practice, this exists to help debug the execution engine. (Implementation bugs can cause pipelines
    * to hang.)
    *
    * Disable the watchdog timer by returning 0.
    *
    * When the [[com.jafpl.runtime.GraphRuntime]] is waiting for a pipeline
    *
    * @return
    */

  def watchdogTimeout: Long
}