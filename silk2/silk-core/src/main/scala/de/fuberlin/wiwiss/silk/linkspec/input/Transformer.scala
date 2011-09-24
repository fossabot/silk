package de.fuberlin.wiwiss.silk.linkspec.input

import de.fuberlin.wiwiss.silk.util.strategy.{Factory, Strategy}

/**
 * Transforms values.
 */
trait Transformer extends Strategy {
  def apply(values: Seq[Set[String]]): Set[String]
}

/**
 * Simple transformer which transforms all values of the first input.
 */
trait SimpleTransformer extends Transformer {
  override final def apply(values: Seq[Set[String]]): Set[String] = {
    values.head.map(evaluate)
  }

  def evaluate(value: String): String
}

object Transformer extends Factory[Transformer]
