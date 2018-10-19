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

package org.apache.spark.storage.memory

import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.LinkedHashMap

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

import com.google.common.io.ByteStreams

import org.apache.spark.{SparkConf, TaskContext}
import org.apache.spark.internal.Logging
import org.apache.spark.memory.{MemoryManager, MemoryMode}
import org.apache.spark.serializer.{SerializationStream, SerializerManager}
import org.apache.spark.storage.{BlockId, BlockInfoManager, StorageLevel, StreamBlockId}
import org.apache.spark.unsafe.Platform
import org.apache.spark.util.{SizeEstimator, Utils}
import org.apache.spark.util.collection.SizeTrackingVector
import org.apache.spark.util.io.{ChunkedByteBuffer, ChunkedByteBufferOutputStream}

private sealed trait MemoryEntry[T] {
  def size: Long
  def memoryMode: MemoryMode
  def classTag: ClassTag[T]
}
private case class DeserializedMemoryEntry[T](
    value: Array[T],
    size: Long,
    classTag: ClassTag[T]) extends MemoryEntry[T] {
  val memoryMode: MemoryMode = MemoryMode.ON_HEAP
}
private case class SerializedMemoryEntry[T](
    buffer: ChunkedByteBuffer,
    memoryMode: MemoryMode,
    classTag: ClassTag[T]) extends MemoryEntry[T] {
  def size: Long = buffer.size
}

private[storage] trait BlockEvictionHandler {
  /**
   * Drop a block from memory, possibly putting it on disk if applicable. Called when the memory
   * store reaches its limit and needs to free up space.
   *
   * If `data` is not put on disk, it won't be created.
   *
   * The caller of this method must hold a write lock on the block before calling this method.
   * This method does not release the write lock.
   *
   * @return the block's new effective StorageLevel.
   */
  private[storage] def dropFromMemory[T: ClassTag](
      blockId: BlockId,
      data: () => Either[Array[T], ChunkedByteBuffer]): StorageLevel
}

/**
 * Stores blocks in memory, either as Arrays of deserialized Java objects or as
 * serialized ByteBuffers.
 */
private[spark] class MemoryStore(
    conf: SparkConf,
    blockInfoManager: BlockInfoManager,
    serializerManager: SerializerManager,
    memoryManager: MemoryManager,
    blockEvictionHandler: BlockEvictionHandler)
  extends Logging {

  // Note: all changes to memory allocations, notably putting blocks, evicting blocks, and
  // acquiring or releasing unroll memory, must be synchronized on `memoryManager`!
  // 对该map 数据结构进行操作的时候需要严格遵循同步的原则， 因为一个Executor会对应一个MemoryStore，
  // 而一个Executor有多个core的时候会并行执行Task，就会有多个线程共享使用一块Storage Memory，
  // 即共享使用这一个LinkedHashMap，修改LinkedHashMap时需要做到同步
  // accessOrder =true 形成一个LRU 算法的队列，被访问到的元素会被加到 链表的最后
  private val entries = new LinkedHashMap[BlockId, MemoryEntry[_]](32, 0.75f, true)

  // A mapping from taskAttemptId to amount of memory used for unrolling a block (in bytes)
  // All accesses of this map are assumed to have manually synchronized on `memoryManager`
  private val onHeapUnrollMemoryMap = mutable.HashMap[Long, Long]()
  // Note: off-heap unroll memory is only used in putIteratorAsBytes() because off-heap caching
  // always stores serialized values.
  private val offHeapUnrollMemoryMap = mutable.HashMap[Long, Long]()

  // Initial memory to request before unrolling any block 1M
  private val unrollMemoryThreshold: Long =
    conf.getLong("spark.storage.unrollMemoryThreshold", 1024 * 1024)

  /** Total amount of memory available for storage, in bytes. */
  private def maxMemory: Long = {
    memoryManager.maxOnHeapStorageMemory + memoryManager.maxOffHeapStorageMemory
  }

  if (maxMemory < unrollMemoryThreshold) {
    logWarning(s"Max memory ${Utils.bytesToString(maxMemory)} is less than the initial memory " +
      s"threshold ${Utils.bytesToString(unrollMemoryThreshold)} needed to store a block in " +
      s"memory. Please configure Spark with more memory.")
  }

  logInfo("MemoryStore started with capacity %s".format(Utils.bytesToString(maxMemory)))

  /** Total storage memory used including unroll memory, in bytes. */
  private def memoryUsed: Long = memoryManager.storageMemoryUsed

  /**
   * Amount of storage memory, in bytes, used for caching blocks.
   * This does not include memory used for unrolling.
   */
  private def blocksMemoryUsed: Long = memoryManager.synchronized {
    memoryUsed - currentUnrollMemory
  }

  def getSize(blockId: BlockId): Long = {
    entries.synchronized {
      entries.get(blockId).size
    }
  }

  /**
   * Use `size` to test if there is enough space in MemoryStore. If so, create the ByteBuffer and
   * put it into MemoryStore. Otherwise, the ByteBuffer won't be created.
   *
   * The caller should guarantee that `size` is correct.
   *
   * @return true if the put() succeeded, false otherwise.
    *
    *         对于经序列化的Partition 在转化为Block 进行存储时， 因为在存储的就知道序列化的ByetBuffer的size
    *       其所需要的unroll 空间， 可以直接累加计算，一次申请。 存储使用putyBytes方法
    *
    *
   */
  def putBytes[T: ClassTag](
      blockId: BlockId,
      size: Long,
      memoryMode: MemoryMode,
      _bytes: () => ChunkedByteBuffer): Boolean = {
    require(!contains(blockId), s"Block $blockId is already present in the MemoryStore")
    if (memoryManager.acquireStorageMemory(blockId, size, memoryMode)) {
      // We acquired enough memory for the block, so go ahead and put it
      val bytes = _bytes()
      assert(bytes.size == size)
      val entry = new SerializedMemoryEntry[T](bytes, memoryMode, implicitly[ClassTag[T]])
      entries.synchronized {
        entries.put(blockId, entry)
      }
      logInfo("Block %s stored as bytes in memory (estimated size %s, free %s)".format(
        blockId, Utils.bytesToString(size), Utils.bytesToString(maxMemory - blocksMemoryUsed)))
      true
    } else {
      false
    }
  }

  /**
   * Attempt to put the given block in memory store as values.
   *
   *
   * It's possible that the iterator is too large to materialize and store in memory. To avoid
   * OOM exceptions, this method will gradually unroll the iterator while periodically checking
   * whether there is enough free memory. If the block is successfully materialized, then the
   * temporary unroll memory used during the materialization is "transferred" to storage memory,
   * so we won't acquire more memory than is actually needed to store the block.
   *
   * @return in case of success, the estimated size of the stored data. In case of failure, return
   *         an iterator containing the values of the block. The returned iterator will be backed
   *         by the combination of the partially-unrolled block and the remaining elements of the
   *         original input iterator. The caller must either fully consume this iterator or call
   *         `close()` on it in order to free the storage memory consumed by the partially-unrolled
   *         block.
   */
  private[storage] def putIteratorAsValues[T](
      blockId: BlockId,
      values: Iterator[T],
      classTag: ClassTag[T]): Either[PartiallyUnrolledIterator[T], Long] = {

    require(!contains(blockId), s"Block $blockId is already present in the MemoryStore")

    // Number of elements unrolled so far 内存展开元素的数量
    var elementsUnrolled = 0
    // Whether there is still enough memory for us to continue unrolling this block
    // 是否存在足够的内存 用于继续展开该数据块：true 表示有，false 表示没有
    var keepUnrolling = true

    // Initial per-task memory to request for unrolling blocks (bytes).
    // 每一个展开线程初始化内存大小  这里设置为conf.getLong("spark.storage.unrollMemoryThreshold", 1024 * 1024)
    val initialMemoryThreshold = unrollMemoryThreshold

    // How often to check whether we need to request more memory
    // 数据块在展开过程中,设置每经过几次展开动作去检查是否申请内存->默认为16
    val memoryCheckPeriod = 16
    // Memory currently reserved by this task for this particular unrolling operation
    // 当前线程保留,用于处理展开操作保留的内存的大小
    var memoryThreshold = initialMemoryThreshold
    // Memory to request as a multiple of current vector size
    // 内存增长因子,每次请求内存大小为:该因子乘以 Vector 大小， 减去 memoryThreshold
    val memoryGrowthFactor = 1.5

    // Keep track of unroll memory used by this particular block / putIterator() operation
    // 展开该数据块,已经使用内存大小
    var unrollMemoryUsedByThisBlock = 0L

    // Underlying vector for unrolling the block
    // 用户跟踪该数据块,展开所使用的内存大小
    var vector = new SizeTrackingVector[T]()(classTag)

    // Request enough memory to begin unrolling
    // 在数据展开之前,根据设置该线程尝试获取初始化 内存
    keepUnrolling =
      reserveUnrollMemoryForThisTask(blockId, initialMemoryThreshold, MemoryMode.ON_HEAP)

    if (!keepUnrolling) {
      logWarning(s"Failed to reserve initial memory threshold of " +
        s"${Utils.bytesToString(initialMemoryThreshold)} for computing block $blockId in memory.")
    } else {
      unrollMemoryUsedByThisBlock += initialMemoryThreshold
    }

    // Unroll this block safely, checking whether we have exceeded our threshold periodically
    // 在内存中 安全展开该数据块  顶起判断是否 超过分配内存大小
    while (values.hasNext && keepUnrolling) {
      vector += values.next()
      // 每 memoryCheckPeriod 即16次展开动作， 进行一次检查展开的内存大小 是否超过 当前分配的内存大小
      if (elementsUnrolled % memoryCheckPeriod == 0) {
        // If our vector's size has exceeded the threshold, request more memory
        //  展开的所需的内存 与 该线程分配到的 内存大小比较， 如果内存充足 则继续展开，
        //  如果内存不足 则根据增长因子 计算需要增加内存大小， 然后根据该大小申请
        val currentSize = vector.estimateSize()
        if (currentSize >= memoryThreshold) {
          val amountToRequest = (currentSize * memoryGrowthFactor - memoryThreshold).toLong

          // 申请需要增加的内存， 如果申请成功，则把内存加到已使用内存中
          // 而该展开线程 获取的内存大小为： 当前展开大小 * 内存增长因子
          keepUnrolling =
            reserveUnrollMemoryForThisTask(blockId, amountToRequest, MemoryMode.ON_HEAP)
          if (keepUnrolling) {
            unrollMemoryUsedByThisBlock += amountToRequest
          }
          // New threshold is currentSize * memoryGrowthFactor
          memoryThreshold += amountToRequest
        }
      }
      elementsUnrolled += 1
    }

    if (keepUnrolling) {
      // We successfully unrolled the entirety of this block
      //  成功的在内存中展开数据块， 估算该数据块在内存中存储的大小
      // 对于非序列化的数据存储， 直接按照array的方式进行存储，可以直接返回装有数据的Iterator
      val arrayValues = vector.toArray
      vector = null
      val entry =
        new DeserializedMemoryEntry[T](arrayValues, SizeEstimator.estimate(arrayValues), classTag)
      val size = entry.size

      // 定义内部方法,该方法中释放该数据块在内存中展开的空间,然后判断该内存是否足够用于写入数据
      def transferUnrollToStorage(amount: Long): Unit = {
        // Synchronize so that transfer is atomic
        memoryManager.synchronized {
          releaseUnrollMemoryForThisTask(MemoryMode.ON_HEAP, amount)
          val success = memoryManager.acquireStorageMemory(blockId, amount, MemoryMode.ON_HEAP)
          assert(success, "transferring unroll memory to storage memory failed")
        }
      }
      // Acquire storage memory if necessary to store this block in memory.
      // 判断内存是否有足够空间保存该数据块
      val enoughStorageMemory = {
        // 判断数据块展开的内存大小 和 数据块存储的大小
        if (unrollMemoryUsedByThisBlock <= size) {
          // 数据块展开的内存 <=  数据块存储大小  说明展开内存大小足以 存储数据块
          //  需要申请 它们之前的差值 如果申请成功
          // 则 调用 transferUnrollToStorage 方法处理
          val acquiredExtra =
            memoryManager.acquireStorageMemory(
              blockId, size - unrollMemoryUsedByThisBlock, MemoryMode.ON_HEAP)
          if (acquiredExtra) {
            transferUnrollToStorage(unrollMemoryUsedByThisBlock)
          }
          acquiredExtra
        } else { // unrollMemoryUsedByThisBlock > size
          // If this task attempt already owns more unroll memory than is necessary to store the
          // block, then release the extra memory that will not be used.
          // 数据块展开内存 > 数据块存储的大小
          // 先释放多余的内存  然后调用transferUnrollToStorage 处理
          val excessUnrollMemory = unrollMemoryUsedByThisBlock - size
          releaseUnrollMemoryForThisTask(MemoryMode.ON_HEAP, excessUnrollMemory)
          transferUnrollToStorage(size)
          true
        }
      }
      if (enoughStorageMemory) {
        // 内存足够  则把数据放到内存enties 中， 并返回占用的内存大小
        entries.synchronized {
          entries.put(blockId, entry)
        }
        logInfo("Block %s stored as values in memory (estimated size %s, free %s)".format(
          blockId, Utils.bytesToString(size), Utils.bytesToString(maxMemory - blocksMemoryUsed)))
        Right(size)
      } else {
        //  如果内存不足  则返回该数据块在 内存部分展开消息 及 大小 等信息
        assert(currentUnrollMemoryForThisTask >= unrollMemoryUsedByThisBlock,
          "released too much unroll memory")
        Left(new PartiallyUnrolledIterator(
          this,
          MemoryMode.ON_HEAP,
          unrollMemoryUsedByThisBlock,
          unrolled = arrayValues.toIterator,
          rest = Iterator.empty))
      }
    } else {
      // We ran out of space while unrolling the values for this block
      logUnrollFailureMessage(blockId, vector.estimateSize())
      Left(new PartiallyUnrolledIterator(
        this,
        MemoryMode.ON_HEAP,
        unrollMemoryUsedByThisBlock,
        unrolled = vector.iterator,
        rest = values))
    }
  }

  /**
   * Attempt to put the given block in memory store as bytes.
   *
   * It's possible that the iterator is too large to materialize and store in memory. To avoid
   * OOM exceptions, this method will gradually unroll the iterator while periodically checking
   * whether there is enough free memory. If the block is successfully materialized, then the
   * temporary unroll memory used during the materialization is "transferred" to storage memory,
   * so we won't acquire more memory than is actually needed to store the block.
   *
   * @return in case of success, the estimated size of the stored data. In case of failure,
   *         return a handle which allows the caller to either finish the serialization by
   *         spilling to disk or to deserialize the partially-serialized block and reconstruct
   *         the original input iterator. The caller must either fully consume this result
   *         iterator or call `discard()` on it in order to free the storage memory consumed by the
   *         partially-unrolled block.
   */
  private[storage] def putIteratorAsBytes[T](
      blockId: BlockId,
      values: Iterator[T],
      classTag: ClassTag[T],
      memoryMode: MemoryMode): Either[PartiallySerializedBlock[T], Long] = {

    require(!contains(blockId), s"Block $blockId is already present in the MemoryStore")

    val allocator = memoryMode match {
      case MemoryMode.ON_HEAP => ByteBuffer.allocate _
      case MemoryMode.OFF_HEAP => Platform.allocateDirectBuffer _
    }

    // Whether there is still enough memory for us to continue unrolling this block
    var keepUnrolling = true
    // Initial per-task memory to request for unrolling blocks (bytes).
    val initialMemoryThreshold = unrollMemoryThreshold
    // Keep track of unroll memory used by this particular block / putIterator() operation
    var unrollMemoryUsedByThisBlock = 0L
    // Underlying buffer for unrolling the block
    val redirectableStream = new RedirectableOutputStream
    val chunkSize = if (initialMemoryThreshold > Int.MaxValue) {
      logWarning(s"Initial memory threshold of ${Utils.bytesToString(initialMemoryThreshold)} " +
        s"is too large to be set as chunk size. Chunk size has been capped to " +
        s"${Utils.bytesToString(Int.MaxValue)}")
      Int.MaxValue
    } else {
      initialMemoryThreshold.toInt
    }
    val bbos = new ChunkedByteBufferOutputStream(chunkSize, allocator)
    redirectableStream.setOutputStream(bbos)
    val serializationStream: SerializationStream = {
      val autoPick = !blockId.isInstanceOf[StreamBlockId]
      val ser = serializerManager.getSerializer(classTag, autoPick).newInstance()
      ser.serializeStream(serializerManager.wrapForCompression(blockId, redirectableStream))
    }

    // Request enough memory to begin unrolling
    keepUnrolling = reserveUnrollMemoryForThisTask(blockId, initialMemoryThreshold, memoryMode)

    if (!keepUnrolling) {
      logWarning(s"Failed to reserve initial memory threshold of " +
        s"${Utils.bytesToString(initialMemoryThreshold)} for computing block $blockId in memory.")
    } else {
      unrollMemoryUsedByThisBlock += initialMemoryThreshold
    }

    def reserveAdditionalMemoryIfNecessary(): Unit = {
      if (bbos.size > unrollMemoryUsedByThisBlock) {
        val amountToRequest = bbos.size - unrollMemoryUsedByThisBlock
        keepUnrolling = reserveUnrollMemoryForThisTask(blockId, amountToRequest, memoryMode)
        if (keepUnrolling) {
          unrollMemoryUsedByThisBlock += amountToRequest
        }
      }
    }

    // Unroll this block safely, checking whether we have exceeded our threshold
    while (values.hasNext && keepUnrolling) {
      serializationStream.writeObject(values.next())(classTag)
      reserveAdditionalMemoryIfNecessary()
    }

    // Make sure that we have enough memory to store the block. By this point, it is possible that
    // the block's actual memory usage has exceeded the unroll memory by a small amount, so we
    // perform one final call to attempt to allocate additional memory if necessary.
    if (keepUnrolling) {
      serializationStream.close()
      reserveAdditionalMemoryIfNecessary()
    }

    if (keepUnrolling) {
      val entry = SerializedMemoryEntry[T](bbos.toChunkedByteBuffer, memoryMode, classTag)
      // Synchronize so that transfer is atomic
      memoryManager.synchronized {
        releaseUnrollMemoryForThisTask(memoryMode, unrollMemoryUsedByThisBlock)
        val success = memoryManager.acquireStorageMemory(blockId, entry.size, memoryMode)
        assert(success, "transferring unroll memory to storage memory failed")
      }
      entries.synchronized {
        entries.put(blockId, entry)
      }
      logInfo("Block %s stored as bytes in memory (estimated size %s, free %s)".format(
        blockId, Utils.bytesToString(entry.size),
        Utils.bytesToString(maxMemory - blocksMemoryUsed)))
      Right(entry.size)
    } else {
      // We ran out of space while unrolling the values for this block
      logUnrollFailureMessage(blockId, bbos.size)
      Left(
        new PartiallySerializedBlock(
          this,
          serializerManager,
          blockId,
          serializationStream,
          redirectableStream,
          unrollMemoryUsedByThisBlock,
          memoryMode,
          bbos,
          values,
          classTag))
    }
  }

  def getBytes(blockId: BlockId): Option[ChunkedByteBuffer] = {
    val entry = entries.synchronized { entries.get(blockId) }
    entry match {
      case null => None
      case e: DeserializedMemoryEntry[_] =>
        throw new IllegalArgumentException("should only call getBytes on serialized blocks")
      case SerializedMemoryEntry(bytes, _, _) => Some(bytes)
    }
  }

  def getValues(blockId: BlockId): Option[Iterator[_]] = {
    val entry = entries.synchronized { entries.get(blockId) }
    entry match {
      case null => None
      case e: SerializedMemoryEntry[_] =>
        throw new IllegalArgumentException("should only call getValues on deserialized blocks")
      case DeserializedMemoryEntry(values, _, _) =>
        val x = Some(values)
        x.map(_.iterator)
    }
  }

  def remove(blockId: BlockId): Boolean = memoryManager.synchronized {
    val entry = entries.synchronized {
      entries.remove(blockId)
    }
    if (entry != null) {
      entry match {
        case SerializedMemoryEntry(buffer, _, _) => buffer.dispose()
        case _ =>
      }
      memoryManager.releaseStorageMemory(entry.size, entry.memoryMode)
      logDebug(s"Block $blockId of size ${entry.size} dropped " +
        s"from memory (free ${maxMemory - blocksMemoryUsed})")
      true
    } else {
      false
    }
  }

  def clear(): Unit = memoryManager.synchronized {
    entries.synchronized {
      entries.clear()
    }
    onHeapUnrollMemoryMap.clear()
    offHeapUnrollMemoryMap.clear()
    memoryManager.releaseAllStorageMemory()
    logInfo("MemoryStore cleared")
  }

  /**
   * Return the RDD ID that a given block ID is from, or None if it is not an RDD block.
   */
  private def getRddId(blockId: BlockId): Option[Int] = {
    blockId.asRDDId.map(_.rddId)
  }

  /**
   * Try to evict blocks to free up a given amount of space to store a particular block.
   * Can fail if either the block is bigger than our memory or it would require replacing
   * another block from the same RDD (which leads to a wasteful cyclic replacement pattern for
   * RDDs that don't fit into memory that we want to avoid).
   *
   * @param blockId the ID of the block we are freeing space for, if any
   * @param space the size of this block
   * @param memoryMode the type of memory to free (on- or off-heap)
   * @return the amount of memory (in bytes) freed by eviction
   */
  private[spark] def evictBlocksToFreeSpace(
      blockId: Option[BlockId],
      space: Long,
      memoryMode: MemoryMode): Long = {
    assert(space > 0)
    memoryManager.synchronized {
      var freedMemory = 0L
      val rddToAdd = blockId.flatMap(getRddId)
      val selectedBlocks = new ArrayBuffer[BlockId]
      def blockIsEvictable(blockId: BlockId, entry: MemoryEntry[_]): Boolean = {
        entry.memoryMode == memoryMode && (rddToAdd.isEmpty || rddToAdd != getRddId(blockId))
      }
      // This is synchronized to ensure that the set of entries is not changed
      // (because of getValue or getBytes) while traversing the iterator, as that
      // can lead to exceptions.
      entries.synchronized {
        val iterator = entries.entrySet().iterator()
        while (freedMemory < space && iterator.hasNext) {
          val pair = iterator.next()
          val blockId = pair.getKey
          val entry = pair.getValue
          if (blockIsEvictable(blockId, entry)) {
            // We don't want to evict blocks which are currently being read, so we need to obtain
            // an exclusive write lock on blocks which are candidates for eviction. We perform a
            // non-blocking "tryLock" here in order to ignore blocks which are locked for reading:
            if (blockInfoManager.lockForWriting(blockId, blocking = false).isDefined) {
              selectedBlocks += blockId
              freedMemory += pair.getValue.size
            }
          }
        }
      }

      def dropBlock[T](blockId: BlockId, entry: MemoryEntry[T]): Unit = {
        val data = entry match {
          case DeserializedMemoryEntry(values, _, _) => Left(values)
          case SerializedMemoryEntry(buffer, _, _) => Right(buffer)
        }
        val newEffectiveStorageLevel =
          blockEvictionHandler.dropFromMemory(blockId, () => data)(entry.classTag)
        if (newEffectiveStorageLevel.isValid) {
          // The block is still present in at least one store, so release the lock
          // but don't delete the block info
          blockInfoManager.unlock(blockId)
        } else {
          // The block isn't present in any store, so delete the block info so that the
          // block can be stored again
          blockInfoManager.removeBlock(blockId)
        }
      }

      if (freedMemory >= space) {
        logInfo(s"${selectedBlocks.size} blocks selected for dropping " +
          s"(${Utils.bytesToString(freedMemory)} bytes)")
        for (blockId <- selectedBlocks) {
          val entry = entries.synchronized { entries.get(blockId) }
          // This should never be null as only one task should be dropping
          // blocks and removing entries. However the check is still here for
          // future safety.
          if (entry != null) {
            dropBlock(blockId, entry)
          }
        }
        logInfo(s"After dropping ${selectedBlocks.size} blocks, " +
          s"free memory is ${Utils.bytesToString(maxMemory - blocksMemoryUsed)}")
        freedMemory
      } else {
        blockId.foreach { id =>
          logInfo(s"Will not store $id")
        }
        selectedBlocks.foreach { id =>
          blockInfoManager.unlock(id)
        }
        0L
      }
    }
  }

  def contains(blockId: BlockId): Boolean = {
    entries.synchronized { entries.containsKey(blockId) }
  }

  private def currentTaskAttemptId(): Long = {
    // In case this is called on the driver, return an invalid task attempt id.
    Option(TaskContext.get()).map(_.taskAttemptId()).getOrElse(-1L)
  }

  /**
   * Reserve memory for unrolling the given block for this task.
   *
   * @return whether the request is granted.
   */
  def reserveUnrollMemoryForThisTask(
      blockId: BlockId,
      memory: Long,
      memoryMode: MemoryMode): Boolean = {
    memoryManager.synchronized {
      val success = memoryManager.acquireUnrollMemory(blockId, memory, memoryMode)
      if (success) {
        val taskAttemptId = currentTaskAttemptId()
        val unrollMemoryMap = memoryMode match {
          case MemoryMode.ON_HEAP => onHeapUnrollMemoryMap
          case MemoryMode.OFF_HEAP => offHeapUnrollMemoryMap
        }
        unrollMemoryMap(taskAttemptId) = unrollMemoryMap.getOrElse(taskAttemptId, 0L) + memory
      }
      success
    }
  }

  /**
   * Release memory used by this task for unrolling blocks.
   * If the amount is not specified, remove the current task's allocation altogether.
   */
  def releaseUnrollMemoryForThisTask(memoryMode: MemoryMode, memory: Long = Long.MaxValue): Unit = {
    val taskAttemptId = currentTaskAttemptId()
    memoryManager.synchronized {
      val unrollMemoryMap = memoryMode match {
        case MemoryMode.ON_HEAP => onHeapUnrollMemoryMap
        case MemoryMode.OFF_HEAP => offHeapUnrollMemoryMap
      }
      if (unrollMemoryMap.contains(taskAttemptId)) {
        val memoryToRelease = math.min(memory, unrollMemoryMap(taskAttemptId))
        if (memoryToRelease > 0) {
          unrollMemoryMap(taskAttemptId) -= memoryToRelease
          memoryManager.releaseUnrollMemory(memoryToRelease, memoryMode)
        }
        if (unrollMemoryMap(taskAttemptId) == 0) {
          unrollMemoryMap.remove(taskAttemptId)
        }
      }
    }
  }

  /**
   * Return the amount of memory currently occupied for unrolling blocks across all tasks.
   */
  def currentUnrollMemory: Long = memoryManager.synchronized {
    onHeapUnrollMemoryMap.values.sum + offHeapUnrollMemoryMap.values.sum
  }

  /**
   * Return the amount of memory currently occupied for unrolling blocks by this task.
   */
  def currentUnrollMemoryForThisTask: Long = memoryManager.synchronized {
    onHeapUnrollMemoryMap.getOrElse(currentTaskAttemptId(), 0L) +
      offHeapUnrollMemoryMap.getOrElse(currentTaskAttemptId(), 0L)
  }

  /**
   * Return the number of tasks currently unrolling blocks.
   */
  private def numTasksUnrolling: Int = memoryManager.synchronized {
    (onHeapUnrollMemoryMap.keys ++ offHeapUnrollMemoryMap.keys).toSet.size
  }

  /**
   * Log information about current memory usage.
   */
  private def logMemoryUsage(): Unit = {
    logInfo(
      s"Memory use = ${Utils.bytesToString(blocksMemoryUsed)} (blocks) + " +
      s"${Utils.bytesToString(currentUnrollMemory)} (scratch space shared across " +
      s"$numTasksUnrolling tasks(s)) = ${Utils.bytesToString(memoryUsed)}. " +
      s"Storage limit = ${Utils.bytesToString(maxMemory)}."
    )
  }

  /**
   * Log a warning for failing to unroll a block.
   *
   * @param blockId ID of the block we are trying to unroll.
   * @param finalVectorSize Final size of the vector before unrolling failed.
   */
  private def logUnrollFailureMessage(blockId: BlockId, finalVectorSize: Long): Unit = {
    logWarning(
      s"Not enough space to cache $blockId in memory! " +
      s"(computed ${Utils.bytesToString(finalVectorSize)} so far)"
    )
    logMemoryUsage()
  }
}

/**
 * The result of a failed [[MemoryStore.putIteratorAsValues()]] call.
 *
 * @param memoryStore  the memoryStore, used for freeing memory.
 * @param memoryMode   the memory mode (on- or off-heap).
 * @param unrollMemory the amount of unroll memory used by the values in `unrolled`.
 * @param unrolled     an iterator for the partially-unrolled values.
 * @param rest         the rest of the original iterator passed to
 *                     [[MemoryStore.putIteratorAsValues()]].
 */
private[storage] class PartiallyUnrolledIterator[T](
    memoryStore: MemoryStore,
    memoryMode: MemoryMode,
    unrollMemory: Long,
    private[this] var unrolled: Iterator[T],
    rest: Iterator[T])
  extends Iterator[T] {

  private def releaseUnrollMemory(): Unit = {
    memoryStore.releaseUnrollMemoryForThisTask(memoryMode, unrollMemory)
    // SPARK-17503: Garbage collects the unrolling memory before the life end of
    // PartiallyUnrolledIterator.
    unrolled = null
  }

  override def hasNext: Boolean = {
    if (unrolled == null) {
      rest.hasNext
    } else if (!unrolled.hasNext) {
      releaseUnrollMemory()
      rest.hasNext
    } else {
      true
    }
  }

  override def next(): T = {
    if (unrolled == null || !unrolled.hasNext) {
      rest.next()
    } else {
      unrolled.next()
    }
  }

  /**
   * Called to dispose of this iterator and free its memory.
   */
  def close(): Unit = {
    if (unrolled != null) {
      releaseUnrollMemory()
    }
  }
}

/**
 * A wrapper which allows an open [[OutputStream]] to be redirected to a different sink.
 */
private[storage] class RedirectableOutputStream extends OutputStream {
  private[this] var os: OutputStream = _
  def setOutputStream(s: OutputStream): Unit = { os = s }
  override def write(b: Int): Unit = os.write(b)
  override def write(b: Array[Byte]): Unit = os.write(b)
  override def write(b: Array[Byte], off: Int, len: Int): Unit = os.write(b, off, len)
  override def flush(): Unit = os.flush()
  override def close(): Unit = os.close()
}

/**
 * The result of a failed [[MemoryStore.putIteratorAsBytes()]] call.
 *
 * @param memoryStore the MemoryStore, used for freeing memory.
 * @param serializerManager the SerializerManager, used for deserializing values.
 * @param blockId the block id.
 * @param serializationStream a serialization stream which writes to [[redirectableOutputStream]].
 * @param redirectableOutputStream an OutputStream which can be redirected to a different sink.
 * @param unrollMemory the amount of unroll memory used by the values in `unrolled`.
 * @param memoryMode whether the unroll memory is on- or off-heap
 * @param bbos byte buffer output stream containing the partially-serialized values.
 *                     [[redirectableOutputStream]] initially points to this output stream.
 * @param rest         the rest of the original iterator passed to
 *                     [[MemoryStore.putIteratorAsValues()]].
 * @param classTag the [[ClassTag]] for the block.
 */
private[storage] class PartiallySerializedBlock[T](
    memoryStore: MemoryStore,
    serializerManager: SerializerManager,
    blockId: BlockId,
    private val serializationStream: SerializationStream,
    private val redirectableOutputStream: RedirectableOutputStream,
    val unrollMemory: Long,
    memoryMode: MemoryMode,
    bbos: ChunkedByteBufferOutputStream,
    rest: Iterator[T],
    classTag: ClassTag[T]) {

  private lazy val unrolledBuffer: ChunkedByteBuffer = {
    bbos.close()
    bbos.toChunkedByteBuffer
  }

  // If the task does not fully consume `valuesIterator` or otherwise fails to consume or dispose of
  // this PartiallySerializedBlock then we risk leaking of direct buffers, so we use a task
  // completion listener here in order to ensure that `unrolled.dispose()` is called at least once.
  // The dispose() method is idempotent, so it's safe to call it unconditionally.
  Option(TaskContext.get()).foreach { taskContext =>
    taskContext.addTaskCompletionListener { _ =>
      // When a task completes, its unroll memory will automatically be freed. Thus we do not call
      // releaseUnrollMemoryForThisTask() here because we want to avoid double-freeing.
      unrolledBuffer.dispose()
    }
  }

  // Exposed for testing
  private[storage] def getUnrolledChunkedByteBuffer: ChunkedByteBuffer = unrolledBuffer

  private[this] var discarded = false
  private[this] var consumed = false

  private def verifyNotConsumedAndNotDiscarded(): Unit = {
    if (consumed) {
      throw new IllegalStateException(
        "Can only call one of finishWritingToStream() or valuesIterator() and can only call once.")
    }
    if (discarded) {
      throw new IllegalStateException("Cannot call methods on a discarded PartiallySerializedBlock")
    }
  }

  /**
   * Called to dispose of this block and free its memory.
   */
  def discard(): Unit = {
    if (!discarded) {
      try {
        // We want to close the output stream in order to free any resources associated with the
        // serializer itself (such as Kryo's internal buffers). close() might cause data to be
        // written, so redirect the output stream to discard that data.
        redirectableOutputStream.setOutputStream(ByteStreams.nullOutputStream())
        serializationStream.close()
      } finally {
        discarded = true
        unrolledBuffer.dispose()
        memoryStore.releaseUnrollMemoryForThisTask(memoryMode, unrollMemory)
      }
    }
  }

  /**
   * Finish writing this block to the given output stream by first writing the serialized values
   * and then serializing the values from the original input iterator.
   */
  def finishWritingToStream(os: OutputStream): Unit = {
    verifyNotConsumedAndNotDiscarded()
    consumed = true
    // `unrolled`'s underlying buffers will be freed once this input stream is fully read:
    ByteStreams.copy(unrolledBuffer.toInputStream(dispose = true), os)
    memoryStore.releaseUnrollMemoryForThisTask(memoryMode, unrollMemory)
    redirectableOutputStream.setOutputStream(os)
    while (rest.hasNext) {
      serializationStream.writeObject(rest.next())(classTag)
    }
    serializationStream.close()
  }

  /**
   * Returns an iterator over the values in this block by first deserializing the serialized
   * values and then consuming the rest of the original input iterator.
   *
   * If the caller does not plan to fully consume the resulting iterator then they must call
   * `close()` on it to free its resources.
   */
  def valuesIterator: PartiallyUnrolledIterator[T] = {
    verifyNotConsumedAndNotDiscarded()
    consumed = true
    // Close the serialization stream so that the serializer's internal buffers are freed and any
    // "end-of-stream" markers can be written out so that `unrolled` is a valid serialized stream.
    serializationStream.close()
    // `unrolled`'s underlying buffers will be freed once this input stream is fully read:
    val unrolledIter = serializerManager.dataDeserializeStream(
      blockId, unrolledBuffer.toInputStream(dispose = true))(classTag)
    // The unroll memory will be freed once `unrolledIter` is fully consumed in
    // PartiallyUnrolledIterator. If the iterator is not consumed by the end of the task then any
    // extra unroll memory will automatically be freed by a `finally` block in `Task`.
    new PartiallyUnrolledIterator(
      memoryStore,
      memoryMode,
      unrollMemory,
      unrolled = unrolledIter,
      rest = rest)
  }
}
