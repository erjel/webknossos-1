package brainflight.binary

import brainflight.tools.geometry.Point3D
import play.api.Logger
import brainflight.tools.geometry.Point3D
import models.binary._
import java.io.{ FileNotFoundException, InputStream, FileInputStream, File }
import akka.agent.Agent
import play.api.libs.concurrent.Promise
import play.api.libs.concurrent.Execution.Implicits._
import brainflight.tools.geometry.Vector3D
import brainflight.tools.Interpolator
import play.api.Logger
import scala.collection.mutable.ArrayBuffer
import akka.actor.Actor
import scala.concurrent.Future
import akka.actor.ActorSystem
import scala.concurrent.duration._
import java.util.concurrent.TimeoutException
import akka.dispatch.OnSuccess
import play.api.Play

case class DataBlock(info: LoadBlock, data: Data)

case class Data(val value: Array[Byte]) extends AnyVal

/**
 * A data store implementation which uses the hdd as data storage
 */
trait DataCache {
  def cache: Agent[Map[LoadBlock, Future[Array[Byte]]]]

  // defines the maximum count of cached file handles
  val maxCacheSize = Play.current.configuration.getInt("bindata.cacheMaxSize") getOrElse 100

  // defines how many file handles are deleted when the limit is reached
  val dropCount = Play.current.configuration.getInt("bindata.cacheDropCount") getOrElse 20

  /**
   * Loads the due to x,y and z defined block into the cache array and
   * returns it.
   */
  def withCache(blockInfo: LoadBlock)(loadF: => Future[Array[Byte]]): Future[Array[Byte]] = {
    ensureCacheMaxSize
    cache().get(blockInfo).getOrElse {
      val p = loadF
      cache send (_ + (blockInfo -> p))
      p.onFailure {
        case e =>
          Logger.warn(s"(${blockInfo.x}, ${blockInfo.y}, ${blockInfo.z}) DataStore couldn't load block: ${e.toString}")
      }
      p
    }
  }

  /**
   * Function to restrict the cache to a maximum size. Should be
   * called before or after an item got inserted into the cache
   */
  def ensureCacheMaxSize {
    // pretends to flood memory with to many files
    if (cache().size > maxCacheSize)
      cache send (_.drop(dropCount))
  }

  /**
   * Called when the store is restarted or going to get shutdown.
   */
  def cleanUp() {
    cache send (_ => Map.empty)
  }

}
