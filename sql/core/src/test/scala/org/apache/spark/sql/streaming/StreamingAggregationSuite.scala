/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.streaming

import java.util.{Locale, TimeZone}

import org.scalatest.Assertions
import org.scalatest.BeforeAndAfterAll

import org.apache.spark.{SparkEnv, SparkException}
import org.apache.spark.rdd.BlockRDD
import org.apache.spark.sql.{AnalysisException, DataFrame, Dataset, SparkSession}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.plans.logical.Aggregate
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.execution.{SparkPlan, UnaryExecNode}
import org.apache.spark.sql.execution.exchange.Exchange
import org.apache.spark.sql.execution.streaming._
import org.apache.spark.sql.execution.streaming.state.StateStore
import org.apache.spark.sql.expressions.scalalang.typed
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.OutputMode._
import org.apache.spark.sql.streaming.util.{MockSourceProvider, StreamManualClock}
import org.apache.spark.sql.types.StructType
import org.apache.spark.storage.{BlockId, StorageLevel, TestBlockId}

object FailureSingleton {
  var firstTime = true
}

class StreamingAggregationSuite extends StateStoreMetricsTest
    with BeforeAndAfterAll with Assertions with StatefulOperatorTest {

  override def afterAll(): Unit = {
    super.afterAll()
    StateStore.stop()
  }

  import testImplicits._

  test("simple count, update mode") {
    val inputData = MemoryStream[Int]

    val aggregated =
      inputData.toDF()
        .groupBy($"value")
        .agg(count("*"))
        .as[(Int, Long)]

    testStream(aggregated, Update)(
      AddData(inputData, 3),
      CheckLastBatch((3, 1)),
      AddData(inputData, 3, 2),
      CheckLastBatch((3, 2), (2, 1)),
      StopStream,
      StartStream(),
      AddData(inputData, 3, 2, 1),
      CheckLastBatch((3, 3), (2, 2), (1, 1)),
      // By default we run in new tuple mode.
      AddData(inputData, 4, 4, 4, 4),
      CheckLastBatch((4, 4))
    )
  }

  test("count distinct") {
    val inputData = MemoryStream[(Int, Seq[Int])]

    val aggregated =
      inputData.toDF()
        .select($"*", explode($"_2") as 'value)
        .groupBy($"_1")
        .agg(size(collect_set($"value")))
        .as[(Int, Int)]

    testStream(aggregated, Update)(
      AddData(inputData, (1, Seq(1, 2))),
      CheckLastBatch((1, 2))
    )
  }

  test("simple count, complete mode") {
    val inputData = MemoryStream[Int]

    val aggregated =
      inputData.toDF()
        .groupBy($"value")
        .agg(count("*"))
        .as[(Int, Long)]

    testStream(aggregated, Complete)(
      AddData(inputData, 3),
      CheckLastBatch((3, 1)),
      AddData(inputData, 2),
      CheckLastBatch((3, 1), (2, 1)),
      StopStream,
      StartStream(),
      AddData(inputData, 3, 2, 1),
      CheckLastBatch((3, 2), (2, 2), (1, 1)),
      AddData(inputData, 4, 4, 4, 4),
      CheckLastBatch((4, 4), (3, 2), (2, 2), (1, 1))
    )
  }

  test("simple count, append mode") {
    val inputData = MemoryStream[Int]

    val aggregated =
      inputData.toDF()
        .groupBy($"value")
        .agg(count("*"))
        .as[(Int, Long)]

    val e = intercept[AnalysisException] {
      testStream(aggregated, Append)()
    }
    Seq("append", "not supported").foreach { m =>
      assert(e.getMessage.toLowerCase(Locale.ROOT).contains(m.toLowerCase(Locale.ROOT)))
    }
  }

  test("sort after aggregate in complete mode") {
    val inputData = MemoryStream[Int]

    val aggregated =
      inputData.toDF()
        .groupBy($"value")
        .agg(count("*"))
        .toDF("value", "count")
        .orderBy($"count".desc)
        .as[(Int, Long)]

    testStream(aggregated, Complete)(
      AddData(inputData, 3),
      CheckLastBatch(isSorted = true, (3, 1)),
      AddData(inputData, 2, 3),
      CheckLastBatch(isSorted = true, (3, 2), (2, 1)),
      StopStream,
      StartStream(),
      AddData(inputData, 3, 2, 1),
      CheckLastBatch(isSorted = true, (3, 3), (2, 2), (1, 1)),
      AddData(inputData, 4, 4, 4, 4),
      CheckLastBatch(isSorted = true, (4, 4), (3, 3), (2, 2), (1, 1))
    )
  }

  test("state metrics") {
    val inputData = MemoryStream[Int]

    val aggregated =
      inputData.toDS()
        .flatMap(x => Seq(x, x + 1))
        .toDF("value")
        .groupBy($"value")
        .agg(count("*"))
        .as[(Int, Long)]

    implicit class RichStreamExecution(query: StreamExecution) {
      def stateNodes: Seq[SparkPlan] = {
        query.lastExecution.executedPlan.collect {
          case p if p.isInstanceOf[StateStoreSaveExec] => p
        }
      }
    }

    // Test with Update mode
    testStream(aggregated, Update)(
      AddData(inputData, 1),
      CheckLastBatch((1, 1), (2, 1)),
      AssertOnQuery { _.stateNodes.size === 1 },
      AssertOnQuery { _.stateNodes.head.metrics.get("numOutputRows").get.value === 2 },
      AssertOnQuery { _.stateNodes.head.metrics.get("numUpdatedStateRows").get.value === 2 },
      AssertOnQuery { _.stateNodes.head.metrics.get("numTotalStateRows").get.value === 2 },
      AddData(inputData, 2, 3),
      CheckLastBatch((2, 2), (3, 2), (4, 1)),
      AssertOnQuery { _.stateNodes.size === 1 },
      AssertOnQuery { _.stateNodes.head.metrics.get("numOutputRows").get.value === 3 },
      AssertOnQuery { _.stateNodes.head.metrics.get("numUpdatedStateRows").get.value === 3 },
      AssertOnQuery { _.stateNodes.head.metrics.get("numTotalStateRows").get.value === 4 }
    )

    // Test with Complete mode
    inputData.reset()
    testStream(aggregated, Complete)(
      AddData(inputData, 1),
      CheckLastBatch((1, 1), (2, 1)),
      AssertOnQuery { _.stateNodes.size === 1 },
      AssertOnQuery { _.stateNodes.head.metrics.get("numOutputRows").get.value === 2 },
      AssertOnQuery { _.stateNodes.head.metrics.get("numUpdatedStateRows").get.value === 2 },
      AssertOnQuery { _.stateNodes.head.metrics.get("numTotalStateRows").get.value === 2 },
      AddData(inputData, 2, 3),
      CheckLastBatch((1, 1), (2, 2), (3, 2), (4, 1)),
      AssertOnQuery { _.stateNodes.size === 1 },
      AssertOnQuery { _.stateNodes.head.metrics.get("numOutputRows").get.value === 4 },
      AssertOnQuery { _.stateNodes.head.metrics.get("numUpdatedStateRows").get.value === 3 },
      AssertOnQuery { _.stateNodes.head.metrics.get("numTotalStateRows").get.value === 4 }
    )
  }

  test("multiple keys") {
    val inputData = MemoryStream[Int]

    val aggregated =
      inputData.toDF()
        .groupBy($"value", $"value" + 1)
        .agg(count("*"))
        .as[(Int, Int, Long)]

    testStream(aggregated, Update)(
      AddData(inputData, 1, 2),
      CheckLastBatch((1, 2, 1), (2, 3, 1)),
      AddData(inputData, 1, 2),
      CheckLastBatch((1, 2, 2), (2, 3, 2))
    )
  }

  testQuietly("midbatch failure") {
    val inputData = MemoryStream[Int]
    FailureSingleton.firstTime = true
    val aggregated =
      inputData.toDS()
          .map { i =>
            if (i == 4 && FailureSingleton.firstTime) {
              FailureSingleton.firstTime = false
              sys.error("injected failure")
            }

            i
          }
          .groupBy($"value")
          .agg(count("*"))
          .as[(Int, Long)]

    testStream(aggregated, Update)(
      StartStream(),
      AddData(inputData, 1, 2, 3, 4),
      ExpectFailure[SparkException](),
      StartStream(),
      CheckLastBatch((1, 1), (2, 1), (3, 1), (4, 1))
    )
  }

  test("typed aggregators") {
    val inputData = MemoryStream[(String, Int)]
    val aggregated = inputData.toDS().groupByKey(_._1).agg(typed.sumLong(_._2))

    testStream(aggregated, Update)(
      AddData(inputData, ("a", 10), ("a", 20), ("b", 1), ("b", 2), ("c", 1)),
      CheckLastBatch(("a", 30), ("b", 3), ("c", 1))
    )
  }

  test("prune results by current_time, complete mode") {
    import testImplicits._
    val clock = new StreamManualClock
    val inputData = MemoryStream[Long]
    val aggregated =
      inputData.toDF()
        .groupBy($"value")
        .agg(count("*"))
        .where('value >= current_timestamp().cast("long") - 10L)

    testStream(aggregated, Complete)(
      StartStream(Trigger.ProcessingTime("10 seconds"), triggerClock = clock),

      // advance clock to 10 seconds, all keys retained
      AddData(inputData, 0L, 5L, 5L, 10L),
      AdvanceManualClock(10 * 1000),
      CheckLastBatch((0L, 1), (5L, 2), (10L, 1)),
      AssertOnQuery(sq =>
        checkChildOutputHashPartitioning[StateStoreRestoreExec](sq, Seq("value"))),

      // advance clock to 20 seconds, should retain keys >= 10
      AddData(inputData, 15L, 15L, 20L),
      AdvanceManualClock(10 * 1000),
      CheckLastBatch((10L, 1), (15L, 2), (20L, 1)),

      // advance clock to 30 seconds, should retain keys >= 20
      AddData(inputData, 0L, 85L),
      AdvanceManualClock(10 * 1000),
      CheckLastBatch((20L, 1), (85L, 1)),

      // bounce stream and ensure correct batch timestamp is used
      // i.e., we don't take it from the clock, which is at 90 seconds.
      StopStream,
      AssertOnQuery { q => // clear the sink
        q.sink.asInstanceOf[MemorySink].clear()
        q.batchCommitLog.purge(3)
        // advance by a minute i.e., 90 seconds total
        clock.advance(60 * 1000L)
        true
      },
      StartStream(Trigger.ProcessingTime("10 seconds"), triggerClock = clock),
      // The commit log blown, causing the last batch to re-run
      CheckLastBatch((20L, 1), (85L, 1)),
      AssertOnQuery { q =>
        clock.getTimeMillis() == 90000L
      },

      // advance clock to 100 seconds, should retain keys >= 90
      AddData(inputData, 85L, 90L, 100L, 105L),
      AdvanceManualClock(10 * 1000),
      CheckLastBatch((90L, 1), (100L, 1), (105L, 1))
    )
  }

  test("prune results by current_date, complete mode") {
    import testImplicits._
    val clock = new StreamManualClock
    val tz = TimeZone.getDefault.getID
    val inputData = MemoryStream[Long]
    val aggregated =
      inputData.toDF()
        .select(to_utc_timestamp(from_unixtime('value * DateTimeUtils.SECONDS_PER_DAY), tz))
        .toDF("value")
        .groupBy($"value")
        .agg(count("*"))
        .where($"value".cast("date") >= date_sub(current_date(), 10))
        .select(($"value".cast("long") / DateTimeUtils.SECONDS_PER_DAY).cast("long"), $"count(1)")
    testStream(aggregated, Complete)(
      StartStream(Trigger.ProcessingTime("10 day"), triggerClock = clock),
      // advance clock to 10 days, should retain all keys
      AddData(inputData, 0L, 5L, 5L, 10L),
      AdvanceManualClock(DateTimeUtils.MILLIS_PER_DAY * 10),
      CheckLastBatch((0L, 1), (5L, 2), (10L, 1)),
      // advance clock to 20 days, should retain keys >= 10
      AddData(inputData, 15L, 15L, 20L),
      AdvanceManualClock(DateTimeUtils.MILLIS_PER_DAY * 10),
      CheckLastBatch((10L, 1), (15L, 2), (20L, 1)),
      // advance clock to 30 days, should retain keys >= 20
      AddData(inputData, 85L),
      AdvanceManualClock(DateTimeUtils.MILLIS_PER_DAY * 10),
      CheckLastBatch((20L, 1), (85L, 1)),

      // bounce stream and ensure correct batch timestamp is used
      // i.e., we don't take it from the clock, which is at 90 days.
      StopStream,
      AssertOnQuery { q => // clear the sink
        q.sink.asInstanceOf[MemorySink].clear()
        q.batchCommitLog.purge(3)
        // advance by 60 days i.e., 90 days total
        clock.advance(DateTimeUtils.MILLIS_PER_DAY * 60)
        true
      },
      StartStream(Trigger.ProcessingTime("10 day"), triggerClock = clock),
      // Commit log blown, causing a re-run of the last batch
      CheckLastBatch((20L, 1), (85L, 1)),

      // advance clock to 100 days, should retain keys >= 90
      AddData(inputData, 85L, 90L, 100L, 105L),
      AdvanceManualClock(DateTimeUtils.MILLIS_PER_DAY * 10),
      CheckLastBatch((90L, 1), (100L, 1), (105L, 1))
    )
  }

  test("SPARK-19690: do not convert batch aggregation in streaming query to streaming") {
    val streamInput = MemoryStream[Int]
    val batchDF = Seq(1, 2, 3, 4, 5)
        .toDF("value")
        .withColumn("parity", 'value % 2)
        .groupBy('parity)
        .agg(count("*") as 'joinValue)
    val joinDF = streamInput
        .toDF()
        .join(batchDF, 'value === 'parity)

    // make sure we're planning an aggregate in the first place
    assert(batchDF.queryExecution.optimizedPlan match { case _: Aggregate => true })

    testStream(joinDF, Append)(
      AddData(streamInput, 0, 1, 2, 3),
      CheckLastBatch((0, 0, 2), (1, 1, 3)),
      AddData(streamInput, 0, 1, 2, 3),
      CheckLastBatch((0, 0, 2), (1, 1, 3)))
  }

  /**
   * This method verifies certain properties in the SparkPlan of a streaming aggregation.
   * First of all, it checks that the child of a `StateStoreRestoreExec` creates the desired
   * data distribution, where the child could be an Exchange, or a `HashAggregateExec` which already
   * provides the expected data distribution.
   *
   * The second thing it checks that the child provides the expected number of partitions.
   *
   * The third thing it checks that we don't add an unnecessary shuffle in-between
   * `StateStoreRestoreExec` and `StateStoreSaveExec`.
   */
  private def checkAggregationChain(
      se: StreamExecution,
      expectShuffling: Boolean,
      expectedPartition: Int): Boolean = {
    val executedPlan = se.lastExecution.executedPlan
    val restore = executedPlan
      .collect { case ss: StateStoreRestoreExec => ss }
      .head
    restore.child match {
      case node: UnaryExecNode =>
        assert(node.outputPartitioning.numPartitions === expectedPartition,
          "Didn't get the expected number of partitions.")
        if (expectShuffling) {
          assert(node.isInstanceOf[Exchange], s"Expected a shuffle, got: ${node.child}")
        } else {
          assert(!node.isInstanceOf[Exchange], "Didn't expect a shuffle")
        }

      case _ => fail("Expected no shuffling")
    }
    var reachedRestore = false
    // Check that there should be no exchanges after `StateStoreRestoreExec`
    executedPlan.foreachUp { p =>
      if (reachedRestore) {
        assert(!p.isInstanceOf[Exchange], "There should be no further exchanges")
      } else {
        reachedRestore = p.isInstanceOf[StateStoreRestoreExec]
      }
    }
    true
  }

  test("SPARK-21977: coalesce(1) with 0 partition RDD should be repartitioned to 1") {
    val inputSource = new BlockRDDBackedSource(spark)
    MockSourceProvider.withMockSources(inputSource) {
      // `coalesce(1)` changes the partitioning of data to `SinglePartition` which by default
      // satisfies the required distributions of all aggregations. Therefore in our SparkPlan, we
      // don't have any shuffling. However, `coalesce(1)` only guarantees that the RDD has at most 1
      // partition. Which means that if we have an input RDD with 0 partitions, nothing gets
      // executed. Therefore the StateStore's don't save any delta files for a given trigger. This
      // then leads to `FileNotFoundException`s in the subsequent batch.
      // This isn't the only problem though. Once we introduce a shuffle before
      // `StateStoreRestoreExec`, the input to the operator is an empty iterator. When performing
      // `groupBy().agg(...)`, `HashAggregateExec` returns a `0` value for all aggregations. If
      // we fail to restore the previous state in `StateStoreRestoreExec`, we save the 0 value in
      // `StateStoreSaveExec` losing all previous state.
      val aggregated: Dataset[Long] =
        spark.readStream.format((new MockSourceProvider).getClass.getCanonicalName)
        .load().coalesce(1).groupBy().count().as[Long]

      testStream(aggregated, Complete())(
        AddBlockData(inputSource, Seq(1)),
        CheckLastBatch(1),
        AssertOnQuery("Verify no shuffling") { se =>
          checkAggregationChain(se, expectShuffling = false, 1)
        },
        AddBlockData(inputSource), // create an empty trigger
        CheckLastBatch(1),
        AssertOnQuery("Verify that no exchange is required") { se =>
          checkAggregationChain(se, expectShuffling = false, 1)
        },
        AddBlockData(inputSource, Seq(2, 3)),
        CheckLastBatch(3),
        AddBlockData(inputSource),
        CheckLastBatch(3),
        StopStream
      )
    }
  }

  test("SPARK-21977: coalesce(1) with aggregation should still be repartitioned when it " +
    "has non-empty grouping keys") {
    val inputSource = new BlockRDDBackedSource(spark)
    MockSourceProvider.withMockSources(inputSource) {
      withTempDir { tempDir =>

        // `coalesce(1)` changes the partitioning of data to `SinglePartition` which by default
        // satisfies the required distributions of all aggregations. However, when we have
        // non-empty grouping keys, in streaming, we must repartition to
        // `spark.sql.shuffle.partitions`, otherwise only a single StateStore is used to process
        // all keys. This may be fine, however, if the user removes the coalesce(1) or changes to
        // a `coalesce(2)` for example, then the default behavior is to shuffle to
        // `spark.sql.shuffle.partitions` many StateStores. When this happens, all StateStore's
        // except 1 will be missing their previous delta files, which causes the stream to fail
        // with FileNotFoundException.
        def createDf(partitions: Int): Dataset[(Long, Long)] = {
          spark.readStream
            .format((new MockSourceProvider).getClass.getCanonicalName)
            .load().coalesce(partitions).groupBy('a % 1).count().as[(Long, Long)]
        }

        testStream(createDf(1), Complete())(
          StartStream(checkpointLocation = tempDir.getAbsolutePath),
          AddBlockData(inputSource, Seq(1)),
          CheckLastBatch((0L, 1L)),
          AssertOnQuery("Verify addition of exchange operator") { se =>
            checkAggregationChain(
              se,
              expectShuffling = true,
              spark.sessionState.conf.numShufflePartitions)
          },
          StopStream
        )

        testStream(createDf(2), Complete())(
          StartStream(checkpointLocation = tempDir.getAbsolutePath),
          Execute(se => se.processAllAvailable()),
          AddBlockData(inputSource, Seq(2), Seq(3), Seq(4)),
          CheckLastBatch((0L, 4L)),
          AssertOnQuery("Verify no exchange added") { se =>
            checkAggregationChain(
              se,
              expectShuffling = false,
              spark.sessionState.conf.numShufflePartitions)
          },
          AddBlockData(inputSource),
          CheckLastBatch((0L, 4L)),
          StopStream
        )
      }
    }
  }

  test("SPARK-22230: last should change with new batches") {
    val input = MemoryStream[Int]

    val aggregated = input.toDF().agg(last('value))
    testStream(aggregated, OutputMode.Complete())(
      AddData(input, 1, 2, 3),
      CheckLastBatch(3),
      AddData(input, 4, 5, 6),
      CheckLastBatch(6),
      AddData(input),
      CheckLastBatch(6),
      AddData(input, 0),
      CheckLastBatch(0)
    )
  }

  /** Add blocks of data to the `BlockRDDBackedSource`. */
  case class AddBlockData(source: BlockRDDBackedSource, data: Seq[Int]*) extends AddData {
    override def addData(query: Option[StreamExecution]): (Source, Offset) = {
      source.addBlocks(data: _*)
      (source, LongOffset(source.counter))
    }
  }

  /**
   * A Streaming Source that is backed by a BlockRDD and that can create RDDs with 0 blocks at will.
   */
  class BlockRDDBackedSource(spark: SparkSession) extends Source {
    var counter = 0L
    private val blockMgr = SparkEnv.get.blockManager
    private var blocks: Seq[BlockId] = Seq.empty

    def addBlocks(dataBlocks: Seq[Int]*): Unit = synchronized {
      dataBlocks.foreach { data =>
        val id = TestBlockId(counter.toString)
        blockMgr.putIterator(id, data.iterator, StorageLevel.MEMORY_ONLY)
        blocks ++= id :: Nil
        counter += 1
      }
      counter += 1
    }

    override def getOffset: Option[Offset] = synchronized {
      if (counter == 0) None else Some(LongOffset(counter))
    }

    override def getBatch(start: Option[Offset], end: Offset): DataFrame = synchronized {
      val rdd = new BlockRDD[Int](spark.sparkContext, blocks.toArray)
        .map(i => InternalRow(i)) // we don't really care about the values in this test
      blocks = Seq.empty
      spark.internalCreateDataFrame(rdd, schema, isStreaming = true).toDF()
    }
    override def schema: StructType = MockSourceProvider.fakeSchema
    override def stop(): Unit = {
      blockMgr.getMatchingBlockIds(_.isInstanceOf[TestBlockId]).foreach(blockMgr.removeBlock(_))
    }
  }
}
