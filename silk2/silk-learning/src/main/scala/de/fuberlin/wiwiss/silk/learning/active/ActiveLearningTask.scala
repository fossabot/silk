/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.fuberlin.wiwiss.silk.learning.active

import de.fuberlin.wiwiss.silk.datasource.Source
import de.fuberlin.wiwiss.silk.util.task.ValueTask
import de.fuberlin.wiwiss.silk.util.DPair
import de.fuberlin.wiwiss.silk.entity.{Link, Path}
import de.fuberlin.wiwiss.silk.config.LinkSpecification
import de.fuberlin.wiwiss.silk.learning.cleaning.CleanPopulationTask
import de.fuberlin.wiwiss.silk.linkagerule.{Operator, LinkageRule}
import de.fuberlin.wiwiss.silk.linkagerule.similarity.{Comparison, Aggregation}
import de.fuberlin.wiwiss.silk.linkagerule.input.{PathInput, TransformInput}
import de.fuberlin.wiwiss.silk.learning.LearningConfiguration
import de.fuberlin.wiwiss.silk.learning.individual.Population
import de.fuberlin.wiwiss.silk.learning.generation.{GeneratePopulationTask, LinkageRuleGenerator}
import de.fuberlin.wiwiss.silk.evaluation.{LinkageRuleEvaluator, ReferenceEntities}
import de.fuberlin.wiwiss.silk.learning.reproduction.{RandomizeTask, ReproductionTask}
import linkselector.{UniformSelector, WeightedLinkageRule}

//TODO support canceling
class ActiveLearningTask(config: LearningConfiguration,
                         sources: Traversable[Source],
                         linkSpec: LinkSpecification,
                         paths: DPair[Seq[Path]],
                         referenceEntities: ReferenceEntities = ReferenceEntities.empty,
                         var pool: Traversable[Link] = Traversable.empty,
                         var population: Population = Population()) extends ValueTask[Seq[Link]](Seq.empty) {

  def links = value.get

  override protected def execute(): Seq[Link] = {
    //Build unlabeled pool
    if(pool.isEmpty) {
      updateStatus("Loading")
      pool = executeSubTask(new GeneratePoolTask(sources, linkSpec, paths), 0.5)
    }

    //Assert that no reference links are in the pool
    pool = pool.filterNot(referenceEntities.positive.contains).filterNot(referenceEntities.negative.contains)

    //Build population
    val generator = LinkageRuleGenerator(ReferenceEntities.fromEntities(pool.map(_.entities.get), Nil), config.components)
    val targetFitness = if(population.isEmpty) 1.0 else population.bestIndividual.fitness

    if(population.isEmpty) {
      updateStatus("Generating population", 0.5)
      val seedRules = if(config.params.seed) linkSpec.rule :: Nil else Nil
      population = executeSubTask(new GeneratePopulationTask(seedRules, generator, config), 0.6, silent = true)
    }

    //Evolve population
    //TODO include CompleteReferenceLinks into fitness function
    val completeEntities = CompleteReferenceLinks(referenceEntities, pool, population)
    val fitnessFunction = config.fitnessFunction(completeEntities)

    for(i <- 0 until config.params.maxIterations
        if i > 0 || population.bestIndividual.fitness < targetFitness
        if LinkageRuleEvaluator(population.bestIndividual.node.build, completeEntities).fMeasure < config.params.destinationfMeasure) {
      val progress = 0.6 + 0.2 * (i + 1) / config.params.maxIterations
      population = executeSubTask(new ReproductionTask(population, fitnessFunction, generator, config), progress, silent = true)
      if(i % config.params.cleanFrequency == 0) {
        population = executeSubTask(new CleanPopulationTask(population, fitnessFunction, generator), progress, silent = true)
      }
    }

    //Select evaluation links
    updateStatus("Selecting evaluation links", 0.8)

    //TODO measure improvement of randomization
    val randomizedPopulation = executeSubTask(new RandomizeTask(population, fitnessFunction, generator, config), 0.8, silent = true)

    val weightedRules = {
      val bestFitness = randomizedPopulation.bestIndividual.fitness
      val topIndividuals = randomizedPopulation.individuals.toSeq.filter(_.fitness >= bestFitness * 0.1).sortBy(-_.fitness)
      for(individual <- topIndividuals) yield {
        new WeightedLinkageRule(individual)
      }
    }

    val valLinks = config.active.selector(weightedRules, pool.toSeq, completeEntities)
    value.update(valLinks)

    //Clean population
    if(referenceEntities.isDefined)
      population = executeSubTask(new CleanPopulationTask(population, fitnessFunction, generator))

    valLinks
  }
}

object ActiveLearningTask {
  def empty = new ActiveLearningTask(LearningConfiguration.default, Traversable.empty, LinkSpecification(), DPair.fill(Seq.empty), ReferenceEntities.empty)
}