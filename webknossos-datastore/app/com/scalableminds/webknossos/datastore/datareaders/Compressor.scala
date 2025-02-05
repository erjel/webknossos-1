package com.scalableminds.webknossos.datastore.datareaders

import com.sun.jna.ptr.NativeLongByReference
import org.apache.commons.compress.compressors.gzip.{
  GzipCompressorInputStream,
  GzipCompressorOutputStream,
  GzipParameters
}
import org.blosc.{BufferSizes, IBloscDll, JBlosc}
import play.api.libs.json.{Format, JsResult, JsValue, Json}

import java.io._
import java.nio.ByteBuffer
import java.util
import java.util.zip.{Deflater, DeflaterOutputStream, Inflater, InflaterInputStream}

sealed trait CompressionSetting
final case class StringCompressionSetting(x: String) extends CompressionSetting
final case class IntCompressionSetting(x: Int) extends CompressionSetting
final case class BoolCompressionSetting(x: Boolean) extends CompressionSetting

object CompressionSetting {
  implicit object CompressionSettingFormat extends Format[CompressionSetting] {

    override def reads(json: JsValue): JsResult[CompressionSetting] =
      json
        .validate[String]
        .map(StringCompressionSetting)
        .orElse(json.validate[Int].map(IntCompressionSetting))
        .orElse(json.validate[Boolean].map(BoolCompressionSetting))

    override def writes(compressionSetting: CompressionSetting): JsValue =
      compressionSetting match {
        case StringCompressionSetting(x) => Json.toJson(x)
        case IntCompressionSetting(x)    => Json.toJson(x)
        case BoolCompressionSetting(x)   => Json.toJson(x)
      }
  }
}

abstract class Compressor {

  def getId: String

  def toString: String

  @throws[IOException]
  def compress(is: InputStream, os: OutputStream): Unit

  @throws[IOException]
  def uncompress(is: InputStream, os: OutputStream): Unit

  @throws[IOException]
  def passThrough(is: InputStream, os: OutputStream): Unit = {
    val bytes = new Array[Byte](4096)
    var read = is.read(bytes)
    while ({ read >= 0 }) {
      if (read > 0)
        os.write(bytes, 0, read)
      read = is.read(bytes)
    }
  }

}

class NullCompressor extends Compressor {
  override def getId: String = null

  override def toString: String = getId

  @throws[IOException]
  override def compress(is: InputStream, os: OutputStream): Unit = passThrough(is, os)

  @throws[IOException]
  override def uncompress(is: InputStream, os: OutputStream): Unit = passThrough(is, os)
}

class ZlibCompressor(val properties: Map[String, CompressionSetting]) extends Compressor {
  val level: Int = properties.get("level") match {
    case None                                        => 1 //default value
    case Some(IntCompressionSetting(levelInt))       => validateLevel(levelInt)
    case Some(StringCompressionSetting(levelString)) => validateLevel(levelString.toInt)
    case _                                           => throw new IllegalArgumentException("Invalid compression level: " + level)
  }

  override def toString: String = "compressor=" + getId + "/level=" + level

  private def validateLevel(level: Int): Int = { // see new Deflater().setLevel(level);
    if (level < 0 || level > 9)
      throw new IllegalArgumentException("Invalid compression level: " + level)
    level
  }

  override def getId = "zlib"

  @throws[IOException]
  override def compress(is: InputStream, os: OutputStream): Unit = {
    val dos = new DeflaterOutputStream(os, new Deflater(level))
    try passThrough(is, dos)
    finally if (dos != null) dos.close()
  }

  @throws[IOException]
  override def uncompress(is: InputStream, os: OutputStream): Unit = {
    val iis = new InflaterInputStream(is, new Inflater)
    try passThrough(iis, os)
    finally if (iis != null) iis.close()
  }
}

class GzipCompressor(val properties: Map[String, CompressionSetting]) extends Compressor {
  val level: Int = properties.get("level") match {
    case None                                        => 1 //default value
    case Some(IntCompressionSetting(levelInt))       => validateLevel(levelInt)
    case Some(StringCompressionSetting(levelString)) => validateLevel(levelString.toInt)
    case _                                           => throw new IllegalArgumentException("Invalid compression level: " + level)
  }

  override def toString: String = "compressor=" + getId + "/level=" + level

  private def validateLevel(level: Int): Int = { // see new Deflater().setLevel(level);
    if (level != -1 && (level < 0 || level > 9))
      throw new IllegalArgumentException("Invalid compression level: " + level)
    level
  }

  override def getId = "gzip"

  @throws[IOException]
  override def compress(is: InputStream, os: OutputStream): Unit = {
    val parameters = new GzipParameters
    parameters.setCompressionLevel(level)
    val dos = new GzipCompressorOutputStream(os, parameters)
    try passThrough(is, dos)
    finally if (dos != null) dos.close()
  }

  @throws[IOException]
  override def uncompress(is: InputStream, os: OutputStream): Unit = {
    val iis = new GzipCompressorInputStream(is, true)
    try passThrough(iis, os)
    finally if (iis != null) iis.close()
  }
}

object BloscCompressor {
  val AUTOSHUFFLE: Int = -1
  val NOSHUFFLE = 0
  val BYTESHUFFLE = 1
  val BITSHUFFLE = 2
  val keyCname = "cname"
  val defaultCname = "lz4"
  val keyClevel = "clevel"
  val defaultCLevel = 5
  val keyShuffle = "shuffle"
  val defaultShuffle: Int = BYTESHUFFLE
  val keyBlocksize = "blocksize"
  val defaultBlocksize = 0
  val supportedShuffle: List[Int] = List(NOSHUFFLE, BYTESHUFFLE, BITSHUFFLE)
  val supportedCnames: List[String] = List("zstd", "blosclz", defaultCname, "lz4hc", "zlib")
}

class BloscCompressor(val properties: Map[String, CompressionSetting]) extends Compressor {
  val cname: String = properties.get(BloscCompressor.keyCname) match {
    case None                                        => BloscCompressor.defaultCname
    case Some(StringCompressionSetting(cnameString)) => validateCname(cnameString)
    case _                                           => throw new IllegalArgumentException("Blosc cname must be string")
  }

  private def validateCname(cname: String) = {
    if (!BloscCompressor.supportedCnames.contains(cname))
      throw new IllegalArgumentException(
        "blosc: compressor not supported: '" + cname + "'; expected one of " +
          BloscCompressor.supportedCnames.mkString(","))
    cname
  }

  val clevel: Int = properties.get(BloscCompressor.keyClevel) match {
    case None                                         => BloscCompressor.defaultCLevel
    case Some(StringCompressionSetting(clevelString)) => validateClevel(clevelString.toInt)
    case Some(IntCompressionSetting(clevelInt))       => validateClevel(clevelInt)
    case _                                            => throw new IllegalArgumentException("Blosc clevel must be int or string")
  }

  private def validateClevel(clevel: Int): Int = {
    if (clevel < 0 || clevel > 9)
      throw new IllegalArgumentException("blosc: clevel parameter must be between 0 and 9 but was: " + clevel)
    clevel
  }

  val shuffle: Int = properties.get(BloscCompressor.keyShuffle) match {
    case None                                          => BloscCompressor.defaultShuffle
    case Some(StringCompressionSetting(shuffleString)) => validateShuffle(shuffleString.toInt)
    case Some(IntCompressionSetting(shuffleInt))       => validateShuffle(shuffleInt)
    case _                                             => throw new IllegalArgumentException("Blosc keyShuffle must be int or string")
  }

  private def validateShuffle(shuffle: Int): Int = {
    val supportedShuffleNames =
      List("0 (NOSHUFFLE)", "1 (BYTESHUFFLE)", "2 (BITSHUFFLE)")

    if (!BloscCompressor.supportedShuffle.contains(shuffle))
      throw new IllegalArgumentException(
        "blosc: shuffle type not supported: '" + shuffle + "'; expected one of " + supportedShuffleNames.mkString(","))
    shuffle
  }

  val blocksize: Int = properties.get(BloscCompressor.keyBlocksize) match {
    case None                                            => BloscCompressor.defaultBlocksize
    case Some(StringCompressionSetting(blockSizeString)) => blockSizeString.toInt
    case Some(IntCompressionSetting(blockSizeInt))       => blockSizeInt
    case _                                               => throw new IllegalArgumentException("Blosc keyBlocksize must be int or string")
  }

  override def getId = "blosc"

  override def toString: String =
    "compressor=" + getId + "/cname=" + cname + "/clevel=" + clevel.toString + "/blocksize=" + blocksize + "/shuffle=" + shuffle

  @throws[IOException]
  override def compress(is: InputStream, os: OutputStream): Unit = {
    val baos = new ByteArrayOutputStream
    passThrough(is, baos)
    val inputBytes = baos.toByteArray
    val inputSize = inputBytes.length
    val outputSize = inputSize + JBlosc.OVERHEAD
    val inputBuffer = ByteBuffer.wrap(inputBytes)
    val outBuffer = ByteBuffer.allocate(outputSize)
    JBlosc.compressCtx(clevel, shuffle, 1, inputBuffer, inputSize, outBuffer, outputSize, cname, blocksize, 1)
    val bs = cbufferSizes(outBuffer)
    val compressedChunk = util.Arrays.copyOfRange(outBuffer.array, 0, bs.getCbytes.toInt)
    os.write(compressedChunk)
  }

  @throws[IOException]
  override def uncompress(is: InputStream, os: OutputStream): Unit = {
    val di = new DataInputStream(is)
    val header = new Array[Byte](JBlosc.OVERHEAD)
    di.readFully(header)
    val bs = cbufferSizes(ByteBuffer.wrap(header))
    val compressedSize = bs.getCbytes.toInt
    val uncompressedSize = bs.getNbytes.toInt
    val inBytes = util.Arrays.copyOf(header, compressedSize)
    di.readFully(inBytes, header.length, compressedSize - header.length)
    val outBuffer = ByteBuffer.allocate(uncompressedSize)
    JBlosc.decompressCtx(ByteBuffer.wrap(inBytes), outBuffer, outBuffer.limit, 1)
    os.write(outBuffer.array)
  }

  private def cbufferSizes(cbuffer: ByteBuffer) = {
    val nbytes = new NativeLongByReference
    val cbytes = new NativeLongByReference
    val blocksize = new NativeLongByReference
    IBloscDll.blosc_cbuffer_sizes(cbuffer, nbytes, cbytes, blocksize)
    val bs = new BufferSizes(nbytes.getValue.longValue, cbytes.getValue.longValue, blocksize.getValue.longValue)
    bs
  }
}
