/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v2_2.profiler

import org.neo4j.cypher.internal.compiler.v2_2.planDescription.InternalPlanDescription
import org.neo4j.cypher.internal.compiler.v2_2.planDescription.InternalPlanDescription.Arguments
import org.neo4j.cypher.internal.compiler.v2_2._
import org.neo4j.cypher.internal.compiler.v2_2.pipes.{SingleRowPipe, Pipe, PipeDecorator, QueryState}
import org.neo4j.cypher.internal.compiler.v2_2.spi.{DelegatingOperations, DelegatingQueryContext, Operations, QueryContext}
import org.neo4j.graphdb.{Node, PropertyContainer, Relationship}

import scala.collection.mutable

class Profiler extends PipeDecorator {

  val dbHitsStats: mutable.Map[Pipe, ProfilingQueryContext] = mutable.Map.empty
  val rowStats: mutable.Map[Pipe, ProfilingIterator] = mutable.Map.empty


  def decorate(pipe: Pipe, iter: Iterator[ExecutionContext]): Iterator[ExecutionContext] = {

    val oldCount = rowStats.get(pipe).map(_.count).getOrElse(0L)
    val resultIter = new ProfilingIterator(iter, oldCount)

    rowStats(pipe) = resultIter
    resultIter
  }

  def decorate(pipe: Pipe, state: QueryState): QueryState = {
    val oldCount = dbHitsStats.get(pipe).map(_.count).getOrElse(0L)
    val decoratedContext = state.query match {
      case p: ProfilingQueryContext => new ProfilingQueryContext(p.inner, pipe, oldCount)
      case _                        => new ProfilingQueryContext(state.query, pipe, oldCount)
    }

    dbHitsStats(pipe) = decoratedContext
    state.copy(query = decoratedContext)
  }


  def decorate(plan: InternalPlanDescription, isProfileReady: => Boolean): InternalPlanDescription = {
    if (!isProfileReady)
      throw new ProfilerStatisticsNotReadyException()

    plan map {
      input: InternalPlanDescription =>
        val pipe = input.pipe
        val rows = rowStats.get(pipe).map(_.count).getOrElse(0L)
        val dbhits = dbHitsStats.get(pipe).map(_.count).getOrElse(0L)

        input
          .addArgument(Arguments.Rows(rows))
          .addArgument(Arguments.DbHits(dbhits))
    }
  }
}

trait Counter {
  protected var _count = 0L
  def count = _count

  def increment() {
    _count += 1L
  }
}

final class ProfilingQueryContext(val inner: QueryContext, val p: Pipe, startValue: Long)
  extends DelegatingQueryContext(inner) with Counter {

  self =>

  _count = startValue

  override protected def singleDbHit[A](value: A): A = {
    increment()
    value
  }

  override protected def manyDbHits[A](value: Iterator[A]): Iterator[A] = {
    increment()
    value.map {
      (v) =>
        increment()
        v
    }
  }

  class ProfilerOperations[T <: PropertyContainer](inner: Operations[T]) extends DelegatingOperations[T](inner) {
    override protected def singleDbHit[A](value: A): A = self.singleDbHit(value)
    override protected def manyDbHits[A](value: Iterator[A]): Iterator[A] = self.manyDbHits(value)
  }

  override def nodeOps: Operations[Node] = new ProfilerOperations(inner.nodeOps)
  override def relationshipOps: Operations[Relationship] = new ProfilerOperations(inner.relationshipOps)
}

class ProfilingIterator(inner: Iterator[ExecutionContext], startValue: Long) extends Iterator[ExecutionContext] with Counter {

  _count = startValue

  def hasNext: Boolean = inner.hasNext

  def next(): ExecutionContext = {
    increment()
    inner.next()
  }
}
