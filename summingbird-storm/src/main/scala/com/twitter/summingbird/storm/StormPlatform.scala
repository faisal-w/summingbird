/*
 Copyright 2013 Twitter, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package com.twitter.summingbird.storm

import backtype.storm.LocalCluster
import backtype.storm.Testing
import backtype.storm.testing.CompleteTopologyParam
import backtype.storm.testing.MockedSources
import backtype.storm.tuple.Fields
import backtype.storm.{Config, StormSubmitter }
import backtype.storm.generated.StormTopology
import backtype.storm.topology.{ BoltDeclarer, TopologyBuilder }
import com.twitter.algebird.Monoid
import com.twitter.bijection.Injection
import com.twitter.chill.InjectionPair
import com.twitter.storehaus.algebra.MergeableStore
import com.twitter.storehaus.algebra.MergeableStore.enrich
import com.twitter.summingbird.batch.{ BatchID, Batcher }
import com.twitter.summingbird.storm.option.{ AnchorTuples, IncludeSuccessHandler }
import com.twitter.summingbird.util.CacheSize
import com.twitter.summingbird.kryo.KryoRegistrationHelper
import com.twitter.tormenta.spout.Spout
import com.twitter.summingbird._
import com.twitter.summingbird.planner._
import com.twitter.summingbird.storm.planner._
import com.twitter.util.Future

import Constants._
import scala.annotation.tailrec

sealed trait StormStore[-K, V] {
  def batcher: Batcher
}

object MergeableStoreSupplier {
  def from[K, V](store: => MergeableStore[(K, BatchID), V])(implicit batcher: Batcher): MergeableStoreSupplier[K, V] =
    MergeableStoreSupplier(() => store, batcher)
}

case class MergeableStoreSupplier[K, V](store: () => MergeableStore[(K, BatchID), V], batcher: Batcher) extends StormStore[K, V]

sealed trait StormService[-K, +V]
case class StoreWrapper[K, V](store: StoreFactory[K, V]) extends StormService[K, V]

object Storm {
  def local(options: Map[String, Options] = Map.empty): LocalStorm =
    new LocalStorm(options, identity)

  def remote(options: Map[String, Options] = Map.empty): RemoteStorm =
    new RemoteStorm(options, identity)

  def timedSpout[T](spout: Spout[T])(implicit timeOf: TimeExtractor[T]): Spout[(Long, T)] =
    spout.map(t => (timeOf(t), t))

  def store[K, V](store: => MergeableStore[(K, BatchID), V])(implicit batcher: Batcher): MergeableStoreSupplier[K, V] =
    MergeableStoreSupplier.from(store)

  implicit def source[T: TimeExtractor: Manifest](spout: Spout[T]) =
    Producer.source[Storm, T](timedSpout(spout))
}

abstract class Storm(options: Map[String, Options], updateConf: Config => Config) extends Platform[Storm] {
  type Source[+T] = Spout[(Long, T)]
  type Store[-K, V] = StormStore[K, V]
  type Sink[-T] = () => (T => Future[Unit])
  type Service[-K, +V] = StormService[K, V]
  type Plan[T] = StormTopology

  private type Prod[T] = Producer[Storm, T]

  private def getOrElse[T: Manifest](node: StormNode, default: T): T = {
    val producer = node.members.last
    
    val namedNodes = Dependants(producer).transitiveDependantsOf(producer).collect{case NamedProducer(_, n) => n}
    (for {
      id <- namedNodes
      stormOpts <- options.get(id)
      option <- stormOpts.get[T]
    } yield option).headOption.getOrElse(default)
  }

  private def scheduleFlatMapper(stormDag: Dag[Storm], node: StormNode)(implicit topologyBuilder: TopologyBuilder) = {
    /**
     * Only exists because of the crazy casts we needed.
     */
    def foldOperations(producers: List[Producer[Storm, _]]): FlatMapOperation[Any, Any] = {
      producers.foldLeft(FlatMapOperation.identity[Any]) {
        case (acc, p) =>
          p match {
            case LeftJoinedProducer(_, StoreWrapper(newService)) =>
              FlatMapOperation.combine(
                acc.asInstanceOf[FlatMapOperation[Any, (Any, Any)]],
                newService.asInstanceOf[StoreFactory[Any, Any]]).asInstanceOf[FlatMapOperation[Any, Any]]
            case OptionMappedProducer(_, op) => acc.andThen(FlatMapOperation[Any, Any](op.andThen(_.iterator).asInstanceOf[Any => TraversableOnce[Any]]))
            case FlatMappedProducer(_, op) => acc.andThen(FlatMapOperation(op).asInstanceOf[FlatMapOperation[Any, Any]])
            case WrittenProducer(_, sinkSupplier) => acc.andThen(FlatMapOperation.write(sinkSupplier.asInstanceOf[() => (Any => Future[Unit])]))
            case IdentityKeyedProducer(_) => acc
            case NamedProducer(_, _) => acc
            case _ => throw new Exception("Not found! : " + p)
          }
      }
    }
    val nodeName = stormDag.getNodeName(node)
    val operation = foldOperations(node.members.reverse)
    val metrics = getOrElse(node, DEFAULT_FM_STORM_METRICS)
    val anchorTuples = getOrElse(node, AnchorTuples.default)

    val summerOpt:Option[SummerNode[Storm]] = stormDag.dependantsOf(node).collect{case s: SummerNode[Storm] => s}.headOption
    
    val bolt = summerOpt match {
      case Some(s) =>
        val summerProducer = s.members.collect { case s: Summer[_, _, _] => s }.head.asInstanceOf[Summer[Storm, _, _]]
        new FinalFlatMapBolt(
          operation.asInstanceOf[FlatMapOperation[Any, (Any, Any)]],
          getOrElse(node, DEFAULT_FM_CACHE),
          getOrElse(node, DEFAULT_FM_STORM_METRICS),
          anchorTuples)(summerProducer.monoid.asInstanceOf[Monoid[Any]], summerProducer.store.batcher)
      case None =>
        new IntermediateFlatMapBolt(operation, metrics, anchorTuples)
    }
    val parallelism = getOrElse(node, DEFAULT_FM_PARALLELISM)
    val declarer = topologyBuilder.setBolt(nodeName, bolt, parallelism.parHint)


    val dependenciesNames = stormDag.dependenciesOf(node).collect { case x: Node => stormDag.getNodeName(x) }
    dependenciesNames.foreach { declarer.shuffleGrouping(_) }
  }

  private def scheduleSpout[K](stormDag: Dag[Storm], node: StormNode)(implicit topologyBuilder: TopologyBuilder) = {
    val spout = node.members.collect { case Source(s) => s }.head
    val nodeName = stormDag.getNodeName(node)

    val stormSpout = node.members.reverse.foldLeft(spout.asInstanceOf[Spout[(Long, Any)]]) {
      case (spout, Source(_)) => spout // The source is still in the members list so drop it
      case (spout, OptionMappedProducer(_, op)) => spout.flatMap {case (time, t) => op.apply(t).map { x => (time, x) }}
      case (spout, NamedProducer(_, _)) => spout
      case _ => sys.error("not possible, given the above call to span.")
    }.getSpout

    val parallelism = getOrElse(node, DEFAULT_SPOUT_PARALLELISM).parHint
    topologyBuilder.setSpout(nodeName, stormSpout, parallelism)
  }

  private def scheduleSinkBolt[K, V](stormDag: Dag[Storm], node: StormNode)(implicit topologyBuilder: TopologyBuilder) = {
    val summer: Summer[Storm, K, V] = node.members.collect { case c: Summer[Storm, K, V] => c }.head
    implicit val monoid = summer.monoid
    val nodeName = stormDag.getNodeName(node)

    val supplier = summer.store match {
      case MergeableStoreSupplier(contained, _) => contained
    }

    val sinkBolt = new SinkBolt[K, V](
      supplier,
      getOrElse(node, DEFAULT_ONLINE_SUCCESS_HANDLER),
      getOrElse(node, DEFAULT_ONLINE_EXCEPTION_HANDLER),
      getOrElse(node, DEFAULT_SINK_CACHE),
      getOrElse(node, DEFAULT_SINK_STORM_METRICS),
      getOrElse(node, DEFAULT_MAX_WAITING_FUTURES),
      getOrElse(node, IncludeSuccessHandler.default))

    val declarer =
      topologyBuilder.setBolt(
        nodeName,
        sinkBolt,
        getOrElse(node, DEFAULT_SINK_PARALLELISM).parHint)
    val dependenciesNames = stormDag.dependenciesOf(node).collect { case x: StormNode => stormDag.getNodeName(x) }
    dependenciesNames.foreach { parentName =>
      declarer.fieldsGrouping(parentName, new Fields(AGG_KEY))
    }

  }

  /**
   * The following operations are public.
   */

  /**
   * Base storm config instances used by the Storm platform.
   */
  def baseConfig = {
    val config = new Config
    config.setFallBackOnJavaSerialization(false)
    config.setKryoFactory(classOf[SummingbirdKryoFactory])
    config.setMaxSpoutPending(1000)
    config.setNumAckers(12)
    config.setNumWorkers(12)
    transformConfig(config)
  }

  def transformConfig(base: Config): Config = updateConf(base)
  def withConfigUpdater(fn: Config => Config): Storm

  def plan[T](tail: Producer[Storm, T]): StormTopology = {
    implicit val topologyBuilder = new TopologyBuilder
    implicit val config = baseConfig

    val stormDag = DagBuilder(tail)

    stormDag.nodes.map { node =>
      node match {
        case _: SummerNode[_] => scheduleSinkBolt(stormDag, node)
        case _: FlatMapNode[_] => scheduleFlatMapper(stormDag, node)
        case _: SourceNode[_] => scheduleSpout(stormDag, node)
      }
    }
    topologyBuilder.createTopology
  }
  def run(summer: Producer[Storm, _], jobName: String): Unit = run(plan(summer), jobName)
  def run(topology: StormTopology, jobName: String): Unit
}

class RemoteStorm(options: Map[String, Options], updateConf: Config => Config) extends Storm(options, updateConf) {

  override def withConfigUpdater(fn: Config => Config) =
    new RemoteStorm(options, updateConf.andThen(fn))

  override def run(topology: StormTopology, jobName: String): Unit = {
    val topologyName = "summingbird_" + jobName
    StormSubmitter.submitTopology(topologyName, baseConfig, topology)
  }
}

class LocalStorm(options: Map[String, Options], updateConf: Config => Config)
  extends Storm(options, updateConf) {
  lazy val localCluster = new LocalCluster

  override def withConfigUpdater(fn: Config => Config) =
    new LocalStorm(options, updateConf.andThen(fn))

  override def run(topology: StormTopology, jobName: String): Unit = {
    val topologyName = "summingbird_" + jobName
    localCluster.submitTopology(topologyName, baseConfig, topology)
  }
}
