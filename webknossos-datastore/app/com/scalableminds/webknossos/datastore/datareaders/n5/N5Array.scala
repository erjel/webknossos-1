package com.scalableminds.webknossos.datastore.datareaders.n5

import com.scalableminds.webknossos.datastore.datareaders.{
  AxisOrder,
  ChunkReader,
  DatasetArray,
  DatasetHeader,
  DatasetPath,
  FileSystemStore
}
import com.typesafe.scalalogging.LazyLogging
import play.api.libs.json.{JsError, JsSuccess, Json}

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path

object N5Array extends LazyLogging {
  @throws[IOException]
  def open(path: Path, axisOrderOpt: Option[AxisOrder], channelIndex: Option[Int]): N5Array = {
    val store = new FileSystemStore(path)
    val rootPath = new DatasetPath("")
    val headerPath = rootPath.resolve(N5Header.FILENAME_ATTRIBUTES_JSON)
    val headerBytes = store.readBytes(headerPath.storeKey)
    if (headerBytes.isEmpty)
      throw new IOException(
        "'" + N5Header.FILENAME_ATTRIBUTES_JSON + "' expected but is not readable or missing in store.")
    val headerString = new String(headerBytes.get, StandardCharsets.UTF_8)
    val header: N5Header =
      Json.parse(headerString).validate[N5Header] match {
        case JsSuccess(parsedHeader, _) =>
          parsedHeader
        case errors: JsError =>
          throw new Exception("Validating json as N5 header failed: " + JsError.toJson(errors).toString())
      }
    if (header.bytesPerChunk > DatasetArray.chunkSizeLimitBytes) {
      throw new IllegalArgumentException(
        f"Chunk size of this N5 Array exceeds limit of ${DatasetArray.chunkSizeLimitBytes}, got ${header.bytesPerChunk}")
    }
    new N5Array(rootPath, store, header, axisOrderOpt.getOrElse(AxisOrder.asZyxFromRank(header.rank)), channelIndex)
  }
}

class N5Array(relativePath: DatasetPath,
              store: FileSystemStore,
              header: DatasetHeader,
              axisOrder: AxisOrder,
              channelIndex: Option[Int])
    extends DatasetArray(relativePath, store, header, axisOrder, channelIndex)
    with LazyLogging {

  override protected val chunkReader: ChunkReader =
    N5ChunkReader.create(store, header)
}
