package org.msgpack.core

import java.io.{EOFException, ByteArrayInputStream, ByteArrayOutputStream}
import scala.util.Random
import org.msgpack.core.buffer.{MessageBuffer, MessageBufferInput, OutputStreamBufferOutput, ArrayBufferInput}
import xerial.core.log.LogLevel
import scala.annotation.tailrec

/**
 * Created on 2014/05/07.
 */
class MessageUnpackerTest extends MessagePackSpec {


  def testData : Array[Byte] = {
    val out = new ByteArrayOutputStream()
    val packer = new MessagePacker(out)

    packer
      .packArrayHeader(2)
      .packInt(1)
      .packString("leo")
      .packArrayHeader(2)
      .packInt(5)
      .packString("aina")

    packer.close()

    val arr = out.toByteArray
    debug(s"packed: ${toHex(arr)}, size:${arr.length}")

    arr
  }

  val intSeq = (for(i <- 0 until 100) yield Random.nextInt()).toArray[Int]

  def testData2 : Array[Byte] = {

    val out = new ByteArrayOutputStream()
    val packer = new MessagePacker(out);

    packer
      .packBoolean(true)
      .packBoolean(false)

    intSeq.foreach(packer.packInt)
    packer.close()

    val arr = out.toByteArray
    debug(s"packed: ${toHex(arr)}")
    arr
  }

  def write(packer:MessagePacker, r:Random) {
    val tpeIndex = Iterator.continually(r.nextInt(MessageFormat.values().length)).find(_ != MessageFormat.NEVER_USED.ordinal()).get
    val tpe = MessageFormat.values()(tpeIndex)
    tpe.getValueType match {

      case ValueType.INTEGER =>
        val v = r.nextInt(Int.MaxValue)
        trace(s"int: $v")
        packer.packInt(v)
      case ValueType.FLOAT =>
        val v = r.nextFloat()
        trace(s"float $v")
        packer.packFloat(v)
      case ValueType.BOOLEAN =>
        val v = r.nextBoolean()
        trace(s"boolean $v")
        packer.packBoolean(v)
      case ValueType.STRING =>
        val v = r.alphanumeric.take(r.nextInt(100)).mkString
        trace(s"string $v")
        packer.packString(v)
      case ValueType.BINARY =>
        val len = r.nextInt(100)
        val b = new Array[Byte](len)
        r.nextBytes(b)
        trace(s"binary: ${toHex(b)}")
        packer.packBinaryHeader(b.length)
        packer.writePayload(b)
      case ValueType.ARRAY =>
        val len = r.nextInt(5)
        trace(s"array len: $len")
        packer.packArrayHeader(len)
        var i = 0
        while(i < len) {
          write(packer, r)
          i += 1
        }
      case ValueType.MAP =>
        val len = r.nextInt(5) + 1
        packer.packMapHeader(len)
        trace(s"map len: ${len}")
        var i = 0
        while(i < len * 2) {
          write(packer, r)
          i += 1
        }
      case _ =>
        val v = r.nextInt(Int.MaxValue)
        trace(s"int: $v")
        packer.packInt(v)
    }
  }

  def testData3(N:Int) : Array[Byte] = {

    val out = new ByteArrayOutputStream()
    val packer = new MessagePacker(out)

    val r = new Random(0)

    (0 until N).foreach { i => write(packer, r) }

    packer.close()
    val arr = out.toByteArray
    trace(s"packed: ${toHex(arr)}")
    debug(s"size:${arr.length}")
    arr
  }


  def readValue(unpacker:MessageUnpacker) {
    val f = unpacker.getNextFormat()
    f.getValueType match {
      case ValueType.ARRAY =>
        val arrLen = unpacker.unpackArrayHeader()
        debug(s"arr size: $arrLen")
      case ValueType.MAP =>
        val mapLen = unpacker.unpackMapHeader()
        debug(s"map size: $mapLen")
      case ValueType.INTEGER =>
        val i = unpacker.unpackInt()
        debug(s"int value: $i")
      case ValueType.STRING =>
        val s = unpacker.unpackString()
        debug(s"str value: $s")
      case other =>
        unpacker.skipValue()
        debug(s"unknown type: $f")
    }
  }


  "MessageUnpacker" should {

    "parse message packed data" taggedAs("unpack") in {
      val arr = testData

      val unpacker = new MessageUnpacker(arr)

      var count = 0
      while(unpacker.hasNext) {
        count += 1
        readValue(unpacker)
      }
      count shouldBe 6
    }

    "skip reading values" in {

      val unpacker = new MessageUnpacker(testData)
      var skipCount = 0
      while(unpacker.hasNext) {
        unpacker.skipValue()
        skipCount += 1
      }

      skipCount shouldBe 2
    }

    "compare skip performance" taggedAs("skip") in {
      val N = 10000
      val data = testData3(N)

      time("skip performance", repeat = 100, logLevel = LogLevel.INFO) {
        block("switch") {
          val unpacker = new MessageUnpacker(data)
          var skipCount = 0
          while(unpacker.hasNext) {
            unpacker.skipValue()
            skipCount += 1
          }
          skipCount shouldBe N
        }
      }

    }

    "parse int data" in {

      debug(intSeq.mkString(", "))

      val ib = Seq.newBuilder[Int]

      val unpacker = new MessageUnpacker(testData2)
      while(unpacker.hasNext) {
        val f = unpacker.getNextFormat
        f.getValueType match {
          case ValueType.INTEGER =>
            val i = unpacker.unpackInt()
            trace(f"read int: $i%,d")
            ib += i
          case ValueType.BOOLEAN =>
            val b = unpacker.unpackBoolean()
            trace(s"read boolean: $b")
          case other =>
            unpacker.skipValue()
        }
      }

      ib.result shouldBe intSeq

    }

    class SplitMessageBufferInput(array:Array[Array[Byte]]) extends MessageBufferInput {
      var cursor = 0
      override def next(): MessageBuffer = {
        if (cursor < array.length) {
          val a = array(cursor)
          cursor += 1
          MessageBuffer.wrap(a)
        }
        else
          null
      }

      override def close(): Unit = {}
    }

    "read data at the buffer boundary" taggedAs("boundary") in {

      trait SplitTest {
        val data : Array[Byte]
        def run {
          val unpacker = new MessageUnpacker(data)
          val numElems = {
            var c = 0
            while (unpacker.hasNext) {
              readValue(unpacker)
              c += 1
            }
            c
          }

          for (splitPoint <- 1 until data.length - 1) {
            debug(s"split at $splitPoint")
            val (h, t) = data.splitAt(splitPoint)
            val bin = new SplitMessageBufferInput(Array(h, t))
            val unpacker = new MessageUnpacker(bin)
            var count = 0
            while (unpacker.hasNext) {
              count += 1
              val f = unpacker.getNextFormat
              readValue(unpacker)
            }
            count shouldBe numElems
          }
        }
      }

      new SplitTest { val data = testData }.run
      new SplitTest { val data = testData3(30) }.run

    }

    "be faster then msgpack-v6 skip" taggedAs("cmp-skip") in {

      val data = testData3(10000)
      val N = 100

      val t = time("skip performance", logLevel = LogLevel.INFO, repeat = N) {
        block("v6") {
          import org.msgpack.`type`.{ValueType => ValueTypeV6}
          val v6 = new org.msgpack.MessagePack()
          val unpacker = new org.msgpack.unpacker.MessagePackUnpacker(v6, new ByteArrayInputStream(data))
          var count = 0
          try {
            while (true) {
              unpacker.skip()
              count += 1
            }
          }
          catch {
            case e: EOFException =>
          }
          finally
            unpacker.close()
        }

        block("v7") {
          val unpacker = new MessageUnpacker(data)
          var count = 0
          try {
            while (unpacker.hasNext) {
              unpacker.skipValue()
              count += 1
            }
          }
          finally
            unpacker.close()
        }
      }

      t("v7").averageWithoutMinMax should be <= t("v6").averageWithoutMinMax
    }


    "be faster than msgpack-v6 read value" taggedAs("cmp-unpack") in {

      import org.msgpack.`type`.{ValueType=>ValueTypeV6}

      def readValueV6(unpacker:org.msgpack.unpacker.MessagePackUnpacker) : Any = {
        val vt = unpacker.getNextType()
        val v = vt match {
          case ValueTypeV6.ARRAY =>
            val len = unpacker.readArrayBegin()
            var i = 0
            while(i < len) { readValueV6(unpacker); i += 1 }
            unpacker.readArrayEnd()
          case ValueTypeV6.MAP =>
            val len = unpacker.readMapBegin()
            var i = 0
            while(i < len) { readValueV6(unpacker); readValueV6(unpacker); i += 1 }
            unpacker.readMapEnd()
          case ValueTypeV6.NIL =>
            unpacker.readNil()
          case ValueTypeV6.INTEGER =>
            unpacker.readLong()
          case ValueTypeV6.BOOLEAN =>
            unpacker.readBoolean()
          case ValueTypeV6.FLOAT =>
            unpacker.readDouble()
          case ValueTypeV6.RAW =>
            unpacker.readByteArray()
          case _ =>
            unpacker.skip()
        }
        v
      }

      val buf = new Array[Byte](8192)

      def readValue(unpacker:MessageUnpacker) : Any = {

        val f = unpacker.getNextFormat
        val vt = f.getValueType
        val v = vt match {
          case ValueType.ARRAY =>
            val len = unpacker.unpackArrayHeader()
            var i = 0
            while(i < len) { readValue(unpacker); i += 1 }
          case ValueType.MAP =>
            val len = unpacker.unpackMapHeader()
            var i = 0
            while(i < len) { readValue(unpacker); readValue(unpacker); i += 1 }
          case ValueType.NIL =>
            unpacker.unpackNil()
          case ValueType.INTEGER =>
            unpacker.unpackLong()
          case ValueType.BOOLEAN =>
            unpacker.unpackBoolean()
          case ValueType.FLOAT =>
            unpacker.unpackDouble()
          case ValueType.STRING =>
            val len = unpacker.unpackRawStringHeader()
            unpacker.readPayload(buf, 0, len)
          case ValueType.BINARY =>
            val len = unpacker.unpackBinaryHeader()
            unpacker.readPayload(buf, 0, len)
          case _ =>
            unpacker.skipValue()
        }
        v
      }

      val data = testData3(10000)
      val N = 100
      val t = time("unpack performance", logLevel=LogLevel.INFO, repeat=N) {
        block("v6") {
          val v6 = new org.msgpack.MessagePack()
          val unpacker = new org.msgpack.unpacker.MessagePackUnpacker(v6, new ByteArrayInputStream(data))
          var count = 0
          try {
            while(true) {
              readValueV6(unpacker)
              count += 1
            }
          }
          catch {
            case e:EOFException =>
          }
          finally
            unpacker.close()
        }

        block("v7") {
          val unpacker = new MessageUnpacker(data)
          var count = 0
          try {
            while (unpacker.hasNext) {
              readValue(unpacker)
              count += 1
            }
          }
          finally
            unpacker.close()
        }
      }

      t("v7").averageWithoutMinMax should be <= t("v6").averageWithoutMinMax


    }



  }

}
