package com.scalableminds.webknossos.datastore.storage

import java.lang.Thread.currentThread
import java.net.URI
import java.nio.file.spi.FileSystemProvider
import java.nio.file.{FileSystem, FileSystemAlreadyExistsException, FileSystems}
import java.util.ServiceLoader
import com.google.common.collect.ImmutableMap
import com.scalableminds.util.cache.LRUConcurrentCache
import com.scalableminds.webknossos.datastore.dataformats.zarr.RemoteSourceDescriptor
import com.scalableminds.webknossos.datastore.s3fs.AmazonS3Factory
import com.typesafe.scalalogging.LazyLogging

import scala.collection.JavaConverters._

class FileSystemsCache(val maxEntries: Int) extends LRUConcurrentCache[RemoteSourceDescriptor, FileSystem]
class FileSystemsProvidersCache(val maxEntries: Int) extends LRUConcurrentCache[String, FileSystemProvider]

object FileSystemsHolder extends LazyLogging {

  private val schemeS3 = "s3"
  private val schemeHttps = "https"
  private val schemeHttp = "http"

  private val fileSystemsCache = new FileSystemsCache(maxEntries = 100)
  private val fileSystemsProvidersCache = new FileSystemsProvidersCache(maxEntries = 100)

  def isSupportedRemoteScheme(uriScheme: String): Boolean =
    List(schemeS3, schemeHttps, schemeHttp).contains(uriScheme)

  def getOrCreate(remoteSource: RemoteSourceDescriptor): Option[FileSystem] =
    fileSystemsCache.getOrLoadAndPutOptional(remoteSource)(loadFromProvider)

  private def loadFromProvider(remoteSource: RemoteSourceDescriptor): Option[FileSystem] = {
    /*
     * The FileSystemProviders can have their own cache for file systems.
     * Those will error on create if the file system already exists.
     * Quirk: They include the user name in the key.
     * This is not supported for newFileSystem but is for getFileSystem.
     * Conversely, getFileSystem cannot be called with the credentials env.
     * Hence this has to be called in two different ways here.
     */
    val uriWithPath = remoteSource.uri
    val uri = baseUri(uriWithPath)
    val uriWithUser = insertUserName(uri, remoteSource)

    val scheme = uri.getScheme
    val credentialsEnv = makeCredentialsEnv(remoteSource, scheme)

    try {
      Some(FileSystems.newFileSystem(uri, credentialsEnv, currentThread().getContextClassLoader))
    } catch {
      case _: FileSystemAlreadyExistsException =>
        try {
          findProviderWithCache(uri.getScheme).map(_.getFileSystem(uriWithUser))
        } catch {
          case e2: Exception =>
            logger.error(s"getFileSytem errored for ${uriWithUser.toString}:", e2)
            None
        }
    }
  }

  private def insertUserName(uri: URI, remoteSource: RemoteSourceDescriptor): URI =
    remoteSource.user.map { user =>
      new URI(uri.getScheme, user, uri.getHost, uri.getPort, uri.getPath, uri.getQuery, uri.getFragment)
    }.getOrElse(uri)

  private def baseUri(uri: URI): URI =
    new URI(uri.getScheme, uri.getUserInfo, uri.getHost, uri.getPort, null, null, null)

  private def makeCredentialsEnv(remoteSource: RemoteSourceDescriptor, scheme: String): ImmutableMap[String, Any] =
    (for {
      user <- remoteSource.user
      password <- remoteSource.password
    } yield {
      if (scheme == schemeS3) {
        ImmutableMap
          .builder[String, Any]
          .put(AmazonS3Factory.ACCESS_KEY, user)
          .put(AmazonS3Factory.SECRET_KEY, password)
          .build
      } else if (scheme == schemeHttps || scheme == schemeHttp) {
        ImmutableMap.builder[String, Any].put("user", user).put("password", password).build
      } else emptyEnv
    }).getOrElse(emptyEnv)

  private def emptyEnv: ImmutableMap[String, Any] = ImmutableMap.builder[String, Any].build()

  private def findProviderWithCache(scheme: String): Option[FileSystemProvider] =
    fileSystemsProvidersCache.getOrLoadAndPutOptional(scheme: String)(findProvider)

  private def findProvider(scheme: String): Option[FileSystemProvider] = {
    val providersIterator =
      ServiceLoader.load(classOf[FileSystemProvider], currentThread().getContextClassLoader).iterator().asScala
    providersIterator.find(p => p.getScheme.equalsIgnoreCase(scheme))
  }

}
