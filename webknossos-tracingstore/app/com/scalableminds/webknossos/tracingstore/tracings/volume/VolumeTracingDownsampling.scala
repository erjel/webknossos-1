package com.scalableminds.webknossos.tracingstore.tracings.volume

import com.scalableminds.util.geometry.Vec3Int
import com.scalableminds.util.tools.{Fox, FoxImplicits}
import com.scalableminds.webknossos.datastore.models.{BucketPosition, UnsignedIntegerArray}
import com.scalableminds.webknossos.datastore.models.datasource.{DataLayerLike, DataSourceLike, ElementClass}
import com.scalableminds.webknossos.tracingstore.TSRemoteWebKnossosClient
import com.scalableminds.webknossos.datastore.VolumeTracing.VolumeTracing
import com.scalableminds.webknossos.tracingstore.tracings.{
  KeyValueStoreImplicits,
  TracingDataStore,
  VersionedKeyValuePair
}
import com.scalableminds.webknossos.datastore.geometry.{Vec3IntProto => ProtoPoint3D}
import com.scalableminds.webknossos.datastore.helpers.ProtoGeometryImplicits
import play.api.libs.json.{Format, Json}

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

object VolumeTracingDownsampling {
  def magsForVolumeTracingByLayerName(dataSource: DataSourceLike, fallbackLayerName: Option[String]): List[Vec3Int] = {
    val fallbackLayer: Option[DataLayerLike] =
      fallbackLayerName.flatMap(name => dataSource.dataLayers.find(_.name == name))
    resolutionsForVolumeTracing(dataSource, fallbackLayer)
  }

  def resolutionsForVolumeTracing(dataSource: DataSourceLike, fallbackLayer: Option[DataLayerLike]): List[Vec3Int] = {
    val fallBackLayerMags = fallbackLayer.map(_.resolutions)
    fallBackLayerMags.getOrElse(dataSource.dataLayers.flatMap(_.resolutions).distinct).sortBy(_.maxDim)
  }
}

trait VolumeTracingDownsampling
    extends BucketKeys
    with ProtoGeometryImplicits
    with VolumeBucketCompression
    with KeyValueStoreImplicits
    with FoxImplicits {

  val tracingDataStore: TracingDataStore
  val tracingStoreWkRpcClient: TSRemoteWebKnossosClient
  def saveBucket(dataLayer: VolumeTracingLayer,
                 bucket: BucketPosition,
                 data: Array[Byte],
                 version: Long,
                 toCache: Boolean = false): Fox[Unit]

  def downsampleWithLayer(tracingId: String,
                          oldTracingId: String,
                          tracing: VolumeTracing,
                          dataLayer: VolumeTracingLayer)(implicit ec: ExecutionContext): Fox[List[Vec3Int]] = {
    val bucketVolume = 32 * 32 * 32
    for {
      _ <- bool2Fox(tracing.version == 0L) ?~> "Tracing has already been edited."
      _ <- bool2Fox(tracing.resolutions.nonEmpty) ?~> "Cannot downsample tracing with no resolution list"
      sourceMag = getSourceMag(tracing)
      magsToCreate <- getMagsToCreate(tracing, oldTracingId)
      elementClass = elementClassFromProto(tracing.elementClass)
      bucketDataMapMutable = new mutable.HashMap[BucketPosition, Array[Byte]]() {
        override def default(key: BucketPosition): Array[Byte] = Array[Byte](0)
      }
      _ = fillMapWithSourceBucketsInplace(bucketDataMapMutable, tracingId, dataLayer, sourceMag)
      originalBucketPositions = bucketDataMapMutable.keys.toList
      updatedBucketsMutable = new mutable.ListBuffer[BucketPosition]()
      _ = magsToCreate.foldLeft(sourceMag) { (previousMag, requiredMag) =>
        downsampleMagFromMag(previousMag,
                             requiredMag,
                             originalBucketPositions,
                             bucketDataMapMutable,
                             updatedBucketsMutable,
                             bucketVolume,
                             elementClass,
                             dataLayer)
        requiredMag
      }
      _ <- Fox.serialCombined(updatedBucketsMutable.toList) { bucketPosition: BucketPosition =>
        saveBucket(dataLayer, bucketPosition, bucketDataMapMutable(bucketPosition), tracing.version)
      }
      _ = logger.debug(s"Downsampled mags $magsToCreate from $sourceMag for volume tracing $tracingId.")
    } yield sourceMag :: magsToCreate
  }

  private def fillMapWithSourceBucketsInplace(bucketDataMap: mutable.HashMap[BucketPosition, Array[Byte]],
                                              tracingId: String,
                                              dataLayer: VolumeTracingLayer,
                                              sourceMag: Vec3Int): Unit = {
    val data: List[VersionedKeyValuePair[Array[Byte]]] =
      tracingDataStore.volumeData.getMultipleKeys(tracingId, Some(tracingId))
    data.foreach { keyValuePair: VersionedKeyValuePair[Array[Byte]] =>
      val bucketPositionOpt = parseBucketKey(keyValuePair.key).map(_._2)
      bucketPositionOpt.foreach { bucketPosition =>
        if (bucketPosition.mag == sourceMag) {
          bucketDataMap(bucketPosition) = decompressIfNeeded(keyValuePair.value,
                                                             expectedUncompressedBucketSizeFor(dataLayer),
                                                             s"bucket $bucketPosition during downsampling")
        }
      }
    }
  }

  private def downsampleMagFromMag(previousMag: Vec3Int,
                                   requiredMag: Vec3Int,
                                   originalBucketPositions: List[BucketPosition],
                                   bucketDataMapMutable: mutable.HashMap[BucketPosition, Array[Byte]],
                                   updatedBucketsMutable: mutable.ListBuffer[BucketPosition],
                                   bucketVolume: Int,
                                   elementClass: ElementClass.Value,
                                   dataLayer: VolumeTracingLayer): Unit = {
    val downScaleFactor =
      Vec3Int(requiredMag.x / previousMag.x, requiredMag.y / previousMag.y, requiredMag.z / previousMag.z)
    downsampledBucketPositions(originalBucketPositions, requiredMag).foreach { downsampledBucketPosition =>
      val sourceBuckets: Seq[BucketPosition] =
        sourceBucketPositionsFor(downsampledBucketPosition, downScaleFactor, previousMag)
      val sourceData: Seq[Array[Byte]] = sourceBuckets.map(bucketDataMapMutable(_))
      val downsampledData: Array[Byte] =
        if (sourceData.forall(_.sameElements(Array[Byte](0))))
          Array[Byte](0)
        else {
          val sourceDataFilled = fillZeroedIfNeeded(sourceData, bucketVolume, dataLayer.bytesPerElement)
          val sourceDataTyped = UnsignedIntegerArray.fromByteArray(sourceDataFilled.toArray.flatten, elementClass)
          val dataDownscaledTyped =
            downsampleData(sourceDataTyped.grouped(bucketVolume).toArray, downScaleFactor, bucketVolume)
          UnsignedIntegerArray.toByteArray(dataDownscaledTyped, elementClass)
        }
      bucketDataMapMutable(downsampledBucketPosition) = downsampledData
      updatedBucketsMutable += downsampledBucketPosition
    }
  }

  private def downsampledBucketPositions(originalBucketPositions: List[BucketPosition],
                                         requiredMag: Vec3Int): Set[BucketPosition] =
    originalBucketPositions.map { bucketPosition: BucketPosition =>
      BucketPosition(
        (bucketPosition.voxelMag1X / requiredMag.x / 32) * requiredMag.x * 32,
        (bucketPosition.voxelMag1Y / requiredMag.y / 32) * requiredMag.y * 32,
        (bucketPosition.voxelMag1Z / requiredMag.z / 32) * requiredMag.z * 32,
        requiredMag
      )
    }.toSet

  private def sourceBucketPositionsFor(bucketPosition: BucketPosition,
                                       downScaleFactor: Vec3Int,
                                       previousMag: Vec3Int): Seq[BucketPosition] =
    for {
      z <- 0 until downScaleFactor.z
      y <- 0 until downScaleFactor.y
      x <- 0 until downScaleFactor.x
    } yield {
      BucketPosition(
        bucketPosition.voxelMag1X + x * bucketPosition.bucketLength * previousMag.x,
        bucketPosition.voxelMag1Y + y * bucketPosition.bucketLength * previousMag.y,
        bucketPosition.voxelMag1Z + z * bucketPosition.bucketLength * previousMag.z,
        previousMag
      )
    }

  private def fillZeroedIfNeeded(sourceData: Seq[Array[Byte]],
                                 bucketVolume: Int,
                                 bytesPerElement: Int): Seq[Array[Byte]] =
    // Reverted buckets and missing buckets are represented by a single zero-byte.
    // For downsampling, those need to be replaced with the full bucket volume of zero-bytes.
    sourceData.map { sourceBucketData =>
      if (sourceBucketData.sameElements(Array[Byte](0))) {
        Array.fill[Byte](bucketVolume * bytesPerElement)(0)
      } else sourceBucketData
    }

  private def downsampleData[T: ClassTag](data: Array[Array[T]],
                                          downScaleFactor: Vec3Int,
                                          bucketVolume: Int): Array[T] = {
    val result = new Array[T](bucketVolume)
    for {
      z <- 0 until 32
      y <- 0 until 32
      x <- 0 until 32
    } {
      val voxelSourceData: IndexedSeq[T] = for {
        z_offset <- 0 until downScaleFactor.z
        y_offset <- 0 until downScaleFactor.y
        x_offset <- 0 until downScaleFactor.x
      } yield {
        val sourceVoxelPosition =
          Vec3Int(x * downScaleFactor.x + x_offset, y * downScaleFactor.y + y_offset, z * downScaleFactor.z + z_offset)
        val sourceBucketPosition =
          Vec3Int(sourceVoxelPosition.x / 32, sourceVoxelPosition.y / 32, sourceVoxelPosition.z / 32)
        val sourceVoxelPositionInSourceBucket =
          Vec3Int(sourceVoxelPosition.x % 32, sourceVoxelPosition.y % 32, sourceVoxelPosition.z % 32)
        val sourceBucketIndex = sourceBucketPosition.x + sourceBucketPosition.y * downScaleFactor.y + sourceBucketPosition.z * downScaleFactor.y * downScaleFactor.z
        val sourceVoxelIndex = sourceVoxelPositionInSourceBucket.x + sourceVoxelPositionInSourceBucket.y * 32 + sourceVoxelPositionInSourceBucket.z * 32 * 32
        data(sourceBucketIndex)(sourceVoxelIndex)
      }
      result(x + y * 32 + z * 32 * 32) = mode(voxelSourceData)
    }
    result
  }

  private def mode[T](items: Seq[T]): T =
    items.groupBy(i => i).mapValues(_.size).maxBy(_._2)._1

  private def getSourceMag(tracing: VolumeTracing): Vec3Int =
    tracing.resolutions.minBy(_.maxDim)

  private def getMagsToCreate(tracing: VolumeTracing, oldTracingId: String): Fox[List[Vec3Int]] =
    for {
      requiredMags <- getRequiredMags(tracing, oldTracingId)
      sourceMag = getSourceMag(tracing)
      magsToCreate = requiredMags.filter(_.maxDim > sourceMag.maxDim)
    } yield magsToCreate

  protected def getRequiredMags(tracing: VolumeTracing, oldTracingId: String): Fox[List[Vec3Int]] =
    for {
      dataSource: DataSourceLike <- tracingStoreWkRpcClient.getDataSourceForTracing(oldTracingId)
      magsForTracing = VolumeTracingDownsampling.magsForVolumeTracingByLayerName(dataSource, tracing.fallbackLayer)
    } yield magsForTracing.sortBy(_.maxDim)

  protected def restrictMagList(tracing: VolumeTracing,
                                resolutionRestrictions: ResolutionRestrictions): VolumeTracing = {
    val tracingResolutions =
      resolveLegacyResolutionList(tracing.resolutions)
    val allowedResolutions = resolutionRestrictions.filterAllowed(tracingResolutions.map(vec3IntFromProto))
    tracing.withResolutions(allowedResolutions.map(vec3IntToProto))
  }

  protected def resolveLegacyResolutionList(resolutions: Seq[ProtoPoint3D]): Seq[ProtoPoint3D] =
    if (resolutions.isEmpty) Seq(ProtoPoint3D(1, 1, 1)) else resolutions
}

object ResolutionRestrictions {
  def empty: ResolutionRestrictions = ResolutionRestrictions(None, None)
  implicit val jsonFormat: Format[ResolutionRestrictions] = Json.format[ResolutionRestrictions]
}

case class ResolutionRestrictions(
    min: Option[Int],
    max: Option[Int]
) {
  def filterAllowed(resolutions: Seq[Vec3Int]): Seq[Vec3Int] =
    resolutions.filter(isAllowed)

  def isAllowed(resolution: Vec3Int): Boolean =
    min.getOrElse(0) <= resolution.maxDim && max.getOrElse(Int.MaxValue) >= resolution.maxDim

  def isForbidden(resolution: Vec3Int): Boolean = !isAllowed(resolution)

  def minStr: Option[String] = min.map(_.toString)
  def maxStr: Option[String] = max.map(_.toString)
}
