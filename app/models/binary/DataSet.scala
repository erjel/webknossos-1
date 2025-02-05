package models.binary

import com.scalableminds.util.accesscontext.{DBAccessContext, GlobalAccessContext}
import com.scalableminds.util.geometry.{BoundingBox, Vec3Double, Vec3Int}
import com.scalableminds.util.tools.{Fox, FoxImplicits, JsonHelper}
import com.scalableminds.webknossos.datastore.models.datasource.DataSetViewConfiguration.DataSetViewConfiguration
import com.scalableminds.webknossos.datastore.models.datasource.LayerViewConfiguration.LayerViewConfiguration
import com.scalableminds.webknossos.datastore.models.datasource.inbox.{InboxDataSourceLike => InboxDataSource}
import com.scalableminds.webknossos.datastore.models.datasource.{
  AbstractDataLayer,
  AbstractSegmentationLayer,
  Category,
  ElementClass,
  DataLayerLike => DataLayer
}
import com.scalableminds.webknossos.schema.Tables._
import javax.inject.Inject
import models.organization.OrganizationDAO
import play.api.libs.json._
import play.utils.UriEncoding
import slick.jdbc.PostgresProfile.api._
import slick.jdbc.TransactionIsolation.Serializable
import slick.lifted.Rep
import slick.sql.SqlAction
import utils.{ObjectId, SQLClient, SQLDAO, SimpleSQLDAO}

import scala.concurrent.ExecutionContext

case class DataSet(
    _id: ObjectId,
    _dataStore: String,
    _organization: ObjectId,
    _publication: Option[ObjectId],
    _uploader: Option[ObjectId],
    inboxSourceHash: Option[Int],
    defaultViewConfiguration: Option[DataSetViewConfiguration] = None,
    adminViewConfiguration: Option[DataSetViewConfiguration] = None,
    description: Option[String] = None,
    displayName: Option[String] = None,
    isPublic: Boolean,
    isUsable: Boolean,
    name: String,
    scale: Option[Vec3Double],
    sharingToken: Option[String],
    status: String,
    logoUrl: Option[String],
    sortingKey: Long = System.currentTimeMillis(),
    details: Option[JsObject] = None,
    tags: Set[String] = Set.empty,
    created: Long = System.currentTimeMillis(),
    isDeleted: Boolean = false
) extends FoxImplicits {

  def urlEncodedName: String =
    UriEncoding.encodePathSegment(name, "UTF-8")
}

class DataSetDAO @Inject()(sqlClient: SQLClient,
                           dataSetDataLayerDAO: DataSetDataLayerDAO,
                           organizationDAO: OrganizationDAO)(implicit ec: ExecutionContext)
    extends SQLDAO[DataSet, DatasetsRow, Datasets](sqlClient) {
  val collection = Datasets

  def idColumn(x: Datasets): Rep[String] = x._Id

  def isDeletedColumn(x: Datasets): Rep[Boolean] = x.isdeleted

  private def parseScaleOpt(literalOpt: Option[String]): Fox[Option[Vec3Double]] = literalOpt match {
    case Some(literal) =>
      for {
        scale <- Vec3Double.fromList(parseArrayTuple(literal).map(_.toDouble)) ?~> "could not parse dataset scale"
      } yield Some(scale)
    case None => Fox.successful(None)
  }

  private def writeScaleLiteral(scale: Vec3Double): String =
    writeStructTuple(List(scale.x, scale.y, scale.z).map(_.toString))

  def parse(r: DatasetsRow): Fox[DataSet] =
    for {
      scale <- parseScaleOpt(r.scale)
      defaultViewConfigurationOpt <- Fox.runOptional(r.defaultviewconfiguration)(
        JsonHelper.parseJsonToFox[DataSetViewConfiguration](_))
      adminViewConfigurationOpt <- Fox.runOptional(r.adminviewconfiguration)(
        JsonHelper.parseJsonToFox[DataSetViewConfiguration](_))
      details <- Fox.runOptional(r.details)(JsonHelper.parseJsonToFox[JsObject](_))
    } yield {
      DataSet(
        ObjectId(r._Id),
        r._Datastore.trim,
        ObjectId(r._Organization),
        r._Publication.map(ObjectId(_)),
        r._Uploader.map(ObjectId(_)),
        r.inboxsourcehash,
        defaultViewConfigurationOpt,
        adminViewConfigurationOpt,
        r.description,
        r.displayname,
        r.ispublic,
        r.isusable,
        r.name,
        scale,
        r.sharingtoken,
        r.status,
        r.logourl,
        r.sortingkey.getTime,
        details,
        parseArrayTuple(r.tags).toSet,
        r.created.getTime,
        r.isdeleted
      )
    }

  override def anonymousReadAccessQ(token: Option[String]): String =
    "isPublic" + token.map(t => s""" or sharingToken = '$t'
               or _id in
                 (select ans._dataset
                 from webknossos.annotation_privateLinks_ apl
                 join webknossos.annotations_ ans ON apl._annotation = ans._id
                 where apl.accessToken = '$t')""").getOrElse("")

  override def readAccessQ(requestingUserId: ObjectId) =
    s"""isPublic
        or _organization in (select _organization from webknossos.users_ where _id = '${requestingUserId.id}' and isAdmin)
        or _id in (select _dataSet
          from (webknossos.dataSet_allowedTeams dt join (select _team from webknossos.user_team_roles where _user = '${requestingUserId.id}') ut on dt._team = ut._team))
        or ('${requestingUserId.id}' in (select _id from webknossos.users where isDatasetManager and _id = '${requestingUserId.id}')
            and _organization in (select _organization from webknossos.users_ where _id = '${requestingUserId.id}'))"""

  override def findOne(id: ObjectId)(implicit ctx: DBAccessContext): Fox[DataSet] =
    for {
      accessQuery <- readAccessQuery
      r <- run(
        sql"select #$columns from #$existingCollectionName where _id = ${id.id} and #$accessQuery".as[DatasetsRow])
      parsed <- parseFirst(r, id)
    } yield parsed

  override def findAll(implicit ctx: DBAccessContext): Fox[List[DataSet]] =
    for {
      accessQuery <- readAccessQuery
      r <- run(sql"select #$columns from #$existingCollectionName where #$accessQuery".as[DatasetsRow])
      parsed <- parseAll(r)
    } yield parsed

  def isEmpty: Fox[Boolean] =
    for {
      r <- run(sql"select count(*) from #$existingCollectionName limit 1".as[Int])
      firstRow <- r.headOption
    } yield firstRow == 0

  def countAllForOrganization(organizationId: ObjectId): Fox[Int] =
    for {
      rList <- run(sql"select count(_id) from #$existingCollectionName where _organization = $organizationId".as[Int])
      r <- rList.headOption
    } yield r

  def findOneByNameAndOrganizationName(name: String, organizationName: String)(
      implicit ctx: DBAccessContext): Fox[DataSet] =
    for {
      organization <- organizationDAO.findOneByName(organizationName)(GlobalAccessContext) ?~> ("organization.notFound " + organizationName)
      dataset <- findOneByNameAndOrganization(name, organization._id)
    } yield dataset

  def findOneByNameAndOrganization(name: String, organizationId: ObjectId)(
      implicit ctx: DBAccessContext): Fox[DataSet] =
    for {
      accessQuery <- readAccessQuery
      r <- run(
        sql"select #$columns from #$existingCollectionName where name = $name and _organization = $organizationId and #$accessQuery"
          .as[DatasetsRow])
      parsed <- parseFirst(r, s"$organizationId/$name")
    } yield parsed

  def findAllByNamesAndOrganization(names: List[String], organizationId: ObjectId)(
      implicit ctx: DBAccessContext): Fox[List[DataSet]] =
    for {
      accessQuery <- readAccessQuery
      r <- run(sql"select #$columns from #$existingCollectionName where name in #${writeStructTupleWithQuotes(
        names.map(sanitize))} and _organization = $organizationId and #$accessQuery".as[DatasetsRow]).map(_.toList)
      parsed <- parseAll(r)
    } yield parsed

  def findAllByPublication(publicationId: ObjectId)(implicit ctx: DBAccessContext): Fox[List[DataSet]] =
    for {
      accessQuery <- readAccessQuery
      r <- run(
        sql"select #$columns from #$existingCollectionName where _publication = $publicationId and #$accessQuery"
          .as[DatasetsRow]).map(_.toList)
      parsed <- parseAll(r)
    } yield parsed

  /* Disambiguation method for legacy URLs and NMLs: if the user has access to multiple datasets of the same name, use the oldest.
   * This is reasonable, because the legacy URL/NML was likely created before this ambiguity became possible */
  def getOrganizationForDataSet(dataSetName: String)(implicit ctx: DBAccessContext): Fox[ObjectId] =
    for {
      accessQuery <- readAccessQuery
      rList <- run(
        sql"select _organization from #$existingCollectionName where name = $dataSetName and #$accessQuery order by created asc"
          .as[String])
      r <- rList.headOption.toFox
      parsed <- ObjectId.fromString(r)
    } yield parsed

  def getNameById(id: ObjectId)(implicit ctx: DBAccessContext): Fox[String] =
    for {
      accessQuery <- readAccessQuery
      rList <- run(sql"select name from #$existingCollectionName where _id = $id and #$accessQuery".as[String])
      r <- rList.headOption.toFox
    } yield r

  def getSharingTokenByName(name: String, organizationId: ObjectId)(
      implicit ctx: DBAccessContext): Fox[Option[String]] =
    for {
      accessQuery <- readAccessQuery
      rList <- run(
        sql"select sharingToken from webknossos.datasets_ where name = $name and _organization = $organizationId and #$accessQuery"
          .as[Option[String]])
      r <- rList.headOption.toFox
    } yield r

  def updateSharingTokenByName(name: String, organizationId: ObjectId, sharingToken: Option[String])(
      implicit ctx: DBAccessContext): Fox[Unit] =
    for {
      accessQuery <- readAccessQuery
      _ <- run(
        sqlu"update webknossos.datasets_ set sharingToken = $sharingToken where name = $name and _organization = $organizationId and #$accessQuery")
    } yield ()

  def updateFields(_id: ObjectId,
                   description: Option[String],
                   displayName: Option[String],
                   sortingKey: Long,
                   isPublic: Boolean)(implicit ctx: DBAccessContext): Fox[Unit] = {
    val q = for { row <- Datasets if notdel(row) && row._Id === _id.id } yield
      (row.description, row.displayname, row.sortingkey, row.ispublic)
    for {
      _ <- assertUpdateAccess(_id)
      _ <- run(q.update(description, displayName, new java.sql.Timestamp(sortingKey), isPublic))
    } yield ()
  }

  def updateTags(id: ObjectId, tags: List[String])(implicit ctx: DBAccessContext): Fox[Unit] =
    for {
      _ <- assertUpdateAccess(id)
      query = writeArrayTuple(tags)
      _ = logger.info(s"updating tags, setting to ${tags.mkString(",")} with sql set tags = '$query'")
      _ <- run(sqlu"update webknossos.datasets set tags = '#$query' where _id = ${id.id}")
    } yield ()

  def updateAdminViewConfiguration(datasetId: ObjectId, configuration: DataSetViewConfiguration)(
      implicit ctx: DBAccessContext): Fox[Unit] =
    for {
      _ <- assertUpdateAccess(datasetId)
      _ <- run(sqlu"""update webknossos.dataSets
                      set adminViewConfiguration = '#${sanitize(Json.toJson(configuration).toString)}'
                      where _id = $datasetId""")
    } yield ()

  def updateUploader(datasetId: ObjectId, uploaderId: Option[ObjectId])(implicit ctx: DBAccessContext): Fox[Unit] =
    for {
      _ <- assertUpdateAccess(datasetId)
      _ <- run(sqlu"""update webknossos.dataSets
                      set _uploader = #${optionLiteral(uploaderId.map(_.id))}
                      where _id = $datasetId""")
    } yield ()

  def insertOne(d: DataSet): Fox[Unit] = {
    val adminViewConfiguration: Option[String] = d.adminViewConfiguration.map(Json.toJson(_).toString)
    val defaultViewConfiguration: Option[String] = d.defaultViewConfiguration.map(Json.toJson(_).toString)
    val details: Option[String] = d.details.map(_.toString)
    for {
      _ <- run(
        sqlu"""insert into webknossos.dataSets(_id, _dataStore, _organization, _publication, _uploader, inboxSourceHash, defaultViewConfiguration, adminViewConfiguration, description, displayName,
                                                             isPublic, isUsable, name, scale, status, sharingToken, sortingKey, details, tags, created, isDeleted)
               values(${d._id.id}, ${d._dataStore}, ${d._organization.id}, #${optionLiteral(d._publication.map(_.id))},
               #${optionLiteral(d._uploader.map(_.id))},
                #${optionLiteral(d.inboxSourceHash.map(_.toString))}, #${optionLiteral(
          defaultViewConfiguration.map(sanitize))}, #${optionLiteral(adminViewConfiguration.map(sanitize))},
                ${d.description}, ${d.displayName}, ${d.isPublic}, ${d.isUsable},
                      ${d.name}, #${optionLiteral(d.scale.map(s => writeScaleLiteral(s)))}, ${d.status
          .take(1024)}, ${d.sharingToken}, ${new java.sql.Timestamp(d.sortingKey)}, #${optionLiteral(details.map(
          sanitize))}, '#${writeArrayTuple(d.tags.toList)}', ${new java.sql.Timestamp(d.created)}, ${d.isDeleted})
            """)
    } yield ()
  }

  def updateDataSourceByNameAndOrganizationName(id: ObjectId,
                                                dataStoreName: String,
                                                inboxSourceHash: Int,
                                                source: InboxDataSource,
                                                isUsable: Boolean)(implicit ctx: DBAccessContext): Fox[Unit] =
    for {
      organization <- organizationDAO.findOneByName(source.id.team)
      defaultViewConfiguration: Option[String] = source.defaultViewConfiguration.map(Json.toJson(_).toString)
      q = sqlu"""update webknossos.dataSets
                    set _dataStore = $dataStoreName,
                        _organization = ${organization._id.id},
                        inboxSourceHash = #${optionLiteral(Some(inboxSourceHash.toString))},
                        defaultViewConfiguration = #${optionLiteral(defaultViewConfiguration)},
                        isUsable = $isUsable,
                        scale = #${optionLiteral(source.scaleOpt.map(s => writeScaleLiteral(s)))},
                        status = ${source.statusOpt.getOrElse("").take(1024)}
                   where _id = $id"""
      _ <- run(q)
      _ <- dataSetDataLayerDAO.updateLayers(id, source)
    } yield ()

  def deactivateUnreported(existingDataSetIds: List[ObjectId],
                           dataStoreName: String,
                           unreportedStatus: String,
                           inactiveStatusList: List[String]): Fox[Unit] = {
    val inclusionPredicate =
      if (existingDataSetIds.isEmpty) "true"
      else s"_id not in ${writeStructTupleWithQuotes(existingDataSetIds.map(_.id))}"
    val statusNotAlreadyInactive = s"status not in ${writeStructTupleWithQuotes(inactiveStatusList)}"
    val deleteResolutionsQuery =
      sqlu"""delete from webknossos.dataSet_resolutions where _dataset in (select _id from webknossos.datasets where _dataStore = $dataStoreName and #$inclusionPredicate)"""
    val deleteLayersQuery =
      sqlu"""delete from webknossos.dataSet_layers where _dataset in (select _id from webknossos.datasets where _dataStore = $dataStoreName and #$inclusionPredicate)"""
    val setToUnusableQuery =
      sqlu"""update webknossos.datasets
             set isUsable = false, status = $unreportedStatus, scale = NULL, inboxSourceHash = NULL
             where _dataStore = $dataStoreName and #$inclusionPredicate and #$statusNotAlreadyInactive"""
    for {
      _ <- run(DBIO.sequence(List(deleteResolutionsQuery, deleteLayersQuery, setToUnusableQuery)).transactionally)
    } yield ()
  }

  def deleteDataset(datasetId: ObjectId): Fox[Unit] = {
    val deleteResolutionsQuery =
      sqlu"delete from webknossos.dataSet_resolutions where _dataset = $datasetId"
    val deleteLayersQuery =
      sqlu"delete from webknossos.dataSet_layers where _dataset = $datasetId"
    val deleteAllowedTeamsQuery = sqlu"delete from webknossos.dataSet_allowedTeams where _dataset = $datasetId"
    val deleteDatasetQuery =
      sqlu"delete from webknossos.datasets where _id = $datasetId"

    for {
      _ <- run(
        DBIO
          .sequence(List(deleteResolutionsQuery, deleteLayersQuery, deleteAllowedTeamsQuery, deleteDatasetQuery))
          .transactionally)
    } yield ()
  }
}

class DataSetResolutionsDAO @Inject()(sqlClient: SQLClient)(implicit ec: ExecutionContext)
    extends SimpleSQLDAO(sqlClient) {
  def parseRow(row: DatasetResolutionsRow): Fox[Vec3Int] =
    for {
      resolution <- Vec3Int.fromList(parseArrayTuple(row.resolution).map(_.toInt)) ?~> "could not parse resolution"
    } yield resolution

  def findDataResolutionForLayer(dataSetId: ObjectId, dataLayerName: String): Fox[List[Vec3Int]] =
    for {
      rows <- run(
        DatasetResolutions.filter(r => r._Dataset === dataSetId.id && r.datalayername === dataLayerName).result)
        .map(_.toList)
      rowsParsed <- Fox.combined(rows.map(parseRow)) ?~> "could not parse resolution row"
    } yield rowsParsed

  def updateResolutions(_dataSet: ObjectId, dataLayersOpt: Option[List[DataLayer]]): Fox[Unit] = {
    val clearQuery = sqlu"delete from webknossos.dataSet_resolutions where _dataSet = ${_dataSet.id}"
    val insertQueries = dataLayersOpt match {
      case Some(dataLayers: List[DataLayer]) =>
        dataLayers.flatMap { layer =>
          layer.resolutions.map { resolution =>
            {
              sqlu"""insert into webknossos.dataSet_resolutions(_dataSet, dataLayerName, resolution)
                       values(${_dataSet.id}, ${layer.name}, '#${writeStructTuple(resolution.toList.map(_.toString))}')"""
            }
          }
        }
      case _ => List()
    }
    for {
      _ <- run(
        DBIO.sequence(List(clearQuery) ++ insertQueries).transactionally.withTransactionIsolation(Serializable),
        retryCount = 50,
        retryIfErrorContains = List(transactionSerializationError)
      )
    } yield ()
  }

}

class DataSetDataLayerDAO @Inject()(sqlClient: SQLClient, dataSetResolutionsDAO: DataSetResolutionsDAO)(
    implicit ec: ExecutionContext)
    extends SimpleSQLDAO(sqlClient) {

  def parseRow(row: DatasetLayersRow, dataSetId: ObjectId, skipResolutions: Boolean = false): Fox[DataLayer] = {
    val result: Fox[Fox[DataLayer]] = for {
      category <- Category.fromString(row.category).toFox ?~> "Could not parse Layer Category"
      boundingBox <- BoundingBox
        .fromSQL(parseArrayTuple(row.boundingbox).map(_.toInt))
        .toFox ?~> "Could not parse boundingbox"
      elementClass <- ElementClass.fromString(row.elementclass).toFox ?~> "Could not parse Layer ElementClass"
      standinResolutions: Option[List[Vec3Int]] = if (skipResolutions) Some(List.empty[Vec3Int]) else None
      resolutions <- Fox.fillOption(standinResolutions)(
        dataSetResolutionsDAO.findDataResolutionForLayer(dataSetId, row.name) ?~> "Could not find resolution for layer")
      defaultViewConfigurationOpt <- Fox.runOptional(row.defaultviewconfiguration)(
        JsonHelper.parseJsonToFox[LayerViewConfiguration](_))
      adminViewConfigurationOpt <- Fox.runOptional(row.adminviewconfiguration)(
        JsonHelper.parseJsonToFox[LayerViewConfiguration](_))
    } yield {
      category match {
        case Category.segmentation =>
          val mappingsAsSet = row.mappings.map(parseArrayTuple(_).toSet)
          Fox.successful(
            AbstractSegmentationLayer(
              row.name,
              category,
              boundingBox,
              resolutions.sortBy(_.maxDim),
              elementClass,
              row.largestsegmentid,
              mappingsAsSet.flatMap(m => if (m.isEmpty) None else Some(m)),
              defaultViewConfigurationOpt,
              adminViewConfigurationOpt
            ))
        case Category.color =>
          Fox.successful(
            AbstractDataLayer(
              row.name,
              category,
              boundingBox,
              resolutions.sortBy(_.maxDim),
              elementClass,
              defaultViewConfigurationOpt,
              adminViewConfigurationOpt
            ))
        case _ => Fox.failure(s"Could not match dataset layer with category $category")
      }
    }
    result.flatten
  }

  def findAllForDataSet(dataSetId: ObjectId, skipResolutions: Boolean = false): Fox[List[DataLayer]] =
    for {
      rows <- run(DatasetLayers.filter(_._Dataset === dataSetId.id).result).map(_.toList)
      rowsParsed <- Fox.combined(rows.map(parseRow(_, dataSetId, skipResolutions)))
    } yield rowsParsed

  private def insertLayerQuery(_dataSet: ObjectId, layer: DataLayer): SqlAction[Int, NoStream, Effect] =
    layer match {
      case s: AbstractSegmentationLayer =>
        val mappings = s.mappings.getOrElse(Set())
        sqlu"""insert into webknossos.dataset_layers(_dataSet, name, category, elementClass, boundingBox, largestSegmentId, mappings, defaultViewConfiguration, adminViewConfiguration)
                    values(${_dataSet.id}, ${s.name}, '#${s.category.toString}', '#${s.elementClass.toString}',
                     '#${writeStructTuple(s.boundingBox.toSql.map(_.toString))}', ${s.largestSegmentId}, '#${writeArrayTuple(
          mappings.toList)}', #${optionLiteral(s.defaultViewConfiguration.map(d => Json.toJson(d).toString))}, #${optionLiteral(
          s.adminViewConfiguration.map(d => Json.toJson(d).toString))})
          on conflict (_dataSet, name) do update set category = '#${s.category.toString}', elementClass = '#${s.elementClass.toString}',
                     boundingBox = '#${writeStructTuple(s.boundingBox.toSql.map(_.toString))}', largestSegmentId = ${s.largestSegmentId},
                     mappings = '#${writeArrayTuple(mappings.toList)}',
            defaultViewConfiguration = #${optionLiteral(s.defaultViewConfiguration.map(d => Json.toJson(d).toString))}"""
      case d: AbstractDataLayer =>
        sqlu"""insert into webknossos.dataset_layers(_dataSet, name, category, elementClass, boundingBox, defaultViewConfiguration, adminViewConfiguration)
                    values(${_dataSet.id}, ${d.name}, '#${d.category.toString}', '#${d.elementClass.toString}',
                     '#${writeStructTuple(d.boundingBox.toSql.map(_.toString))}', #${optionLiteral(
          d.defaultViewConfiguration.map(d => Json.toJson(d).toString))}, #${optionLiteral(
          d.adminViewConfiguration.map(d => Json.toJson(d).toString))})
          on conflict (_dataSet, name) do update set category = '#${d.category.toString}', elementClass = '#${d.elementClass.toString}',
                     boundingBox = '#${writeStructTuple(d.boundingBox.toSql.map(_.toString))}',
            defaultViewConfiguration = #${optionLiteral(d.defaultViewConfiguration.map(d => Json.toJson(d).toString))}"""
      case _ => throw new Exception("DataLayer type mismatch")
    }

  def updateLayers(_dataSet: ObjectId, source: InboxDataSource): Fox[Unit] = {
    def getSpecificClearQuery(dataLayers: List[DataLayer]) =
      sqlu"delete from webknossos.dataset_layers where _dataSet = ${_dataSet} and name not in #${writeStructTupleWithQuotes(
        dataLayers.map(d => sanitize(d.name)))}"
    val clearQuery = sqlu"delete from webknossos.dataset_layers where _dataSet = ${_dataSet}"

    val queries = source.toUsable match {
      case Some(usable) =>
        getSpecificClearQuery(usable.dataLayers) :: usable.dataLayers.map(insertLayerQuery(_dataSet, _))
      case _ => List(clearQuery)
    }
    for {
      _ <- run(DBIO.sequence(queries))
      _ <- dataSetResolutionsDAO.updateResolutions(_dataSet, source.toUsable.map(_.dataLayers))
    } yield ()
  }

  def updateLayerAdminViewConfiguration(_dataSet: ObjectId,
                                        layerName: String,
                                        adminViewConfiguration: LayerViewConfiguration): Fox[Unit] = {
    val q =
      sqlu"""update webknossos.dataset_layers
            set adminViewConfiguration = '#${sanitize(Json.toJson(adminViewConfiguration).toString)}'
            where _dataSet = ${_dataSet} and name = $layerName"""
    run(q).map(_ => ())
  }
}

class DataSetAllowedTeamsDAO @Inject()(sqlClient: SQLClient)(implicit ec: ExecutionContext)
    extends SimpleSQLDAO(sqlClient) {

  def findAllForDataSet(dataSetId: ObjectId): Fox[List[ObjectId]] = {
    val query = for {
      (_, team) <- DatasetAllowedteams.filter(_._Dataset === dataSetId.id) join Teams on (_._Team === _._Id)
    } yield team._Id

    run(query.result).flatMap(rows => Fox.serialCombined(rows.toList)(ObjectId.fromString(_)))
  }

  def updateAllowedTeamsForDataSet(_id: ObjectId, allowedTeams: List[ObjectId]): Fox[Unit] = {
    val clearQuery = sqlu"delete from webknossos.dataSet_allowedTeams where _dataSet = ${_id}"

    val insertQueries = allowedTeams.map(teamId => sqlu"""insert into webknossos.dataSet_allowedTeams(_dataSet, _team)
                                                              values(${_id}, ${teamId.id})""")

    val composedQuery = DBIO.sequence(List(clearQuery) ++ insertQueries)
    for {
      _ <- run(composedQuery.transactionally.withTransactionIsolation(Serializable),
               retryCount = 50,
               retryIfErrorContains = List(transactionSerializationError))
    } yield ()
  }

  def removeTeamFromAllDatasets(teamId: ObjectId): Fox[Unit] =
    for {
      _ <- run(sqlu"delete from webknossos.dataSet_allowedTeams where _team = $teamId")
    } yield ()

}

class DataSetLastUsedTimesDAO @Inject()(sqlClient: SQLClient)(implicit ec: ExecutionContext)
    extends SimpleSQLDAO(sqlClient) {
  def findForDataSetAndUser(dataSetId: ObjectId, userId: ObjectId): Fox[Long] =
    for {
      rList <- run(
        sql"select lastUsedTime from webknossos.dataSet_lastUsedTimes where _dataSet = $dataSetId and _user = $userId"
          .as[java.sql.Timestamp])
      r <- rList.headOption.toFox
    } yield r.getTime

  def updateForDataSetAndUser(dataSetId: ObjectId, userId: ObjectId): Fox[Unit] = {
    val clearQuery =
      sqlu"delete from webknossos.dataSet_lastUsedTimes where _dataSet = $dataSetId and _user = $userId"
    val insertQuery =
      sqlu"insert into webknossos.dataSet_lastUsedTimes(_dataSet, _user, lastUsedTime) values($dataSetId, $userId, NOW())"
    val composedQuery = DBIO.sequence(List(clearQuery, insertQuery))
    for {
      _ <- run(composedQuery.transactionally.withTransactionIsolation(Serializable),
               retryCount = 50,
               retryIfErrorContains = List(transactionSerializationError))
    } yield ()
  }
}
