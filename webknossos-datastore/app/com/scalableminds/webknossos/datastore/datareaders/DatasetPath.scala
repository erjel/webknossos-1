package com.scalableminds.webknossos.datastore.datareaders

class DatasetPath(storeKeyRaw: String) {
  lazy val storeKey: String = normalizeStoragePath(storeKeyRaw)

  def resolve(name: String): DatasetPath =
    new DatasetPath(storeKey + "/" + normalizeStoragePath(name))

  def normalizeStoragePath(path: String): String =
    if (path.isEmpty)
      path
    else {
      var pathMutable = path
      //replace backslashes with slashes
      pathMutable = pathMutable.replace("\\", "/")
      // collapse any repeated slashes
      while ({
        pathMutable.contains("//")
      }) pathMutable = pathMutable.replace("//", "/")
      // ensure no leading slash
      if (pathMutable.startsWith("/")) pathMutable = pathMutable.substring(1)
      // ensure no trailing slash
      if (pathMutable.endsWith("/")) pathMutable = pathMutable.substring(0, path.length - 1)
      // don't allow path segments with just '.' or '..'
      for (segment <- pathMutable.split("/")) {
        if (segment.trim == "." || segment.trim == "..")
          throw new IllegalArgumentException("path containing '.' or '..' segment not allowed")
      }
      pathMutable
    }

}
