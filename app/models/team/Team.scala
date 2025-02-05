package models.team

import com.scalableminds.util.accesscontext.{DBAccessContext, GlobalAccessContext}
import com.scalableminds.util.tools.{Fox, FoxImplicits}
import com.scalableminds.webknossos.schema.Tables._
import javax.inject.Inject
import models.annotation.AnnotationDAO
import models.organization.{Organization, OrganizationDAO}
import models.project.ProjectDAO
import models.task.TaskTypeDAO
import models.user.User
import play.api.i18n.{Messages, MessagesProvider}
import play.api.libs.json._
import slick.jdbc.PostgresProfile.api._
import slick.lifted.Rep
import utils.{ObjectId, SQLClient, SQLDAO}

import scala.concurrent.ExecutionContext

case class Team(
    _id: ObjectId,
    _organization: ObjectId,
    name: String,
    isOrganizationTeam: Boolean = false,
    created: Long = System.currentTimeMillis(),
    isDeleted: Boolean = false
) extends FoxImplicits {

  def couldBeAdministratedBy(user: User): Boolean =
    user._organization == this._organization

}

class TeamService @Inject()(organizationDAO: OrganizationDAO,
                            annotationDAO: AnnotationDAO,
                            projectDAO: ProjectDAO,
                            taskTypeDAO: TaskTypeDAO)(implicit ec: ExecutionContext)
    extends FoxImplicits {

  def publicWrites(team: Team, organizationOpt: Option[Organization] = None): Fox[JsObject] =
    for {
      organization <- Fox.fillOption(organizationOpt)(organizationDAO.findOne(team._organization)(GlobalAccessContext))
    } yield {
      Json.obj(
        "id" -> team._id.toString,
        "name" -> team.name,
        "organization" -> organization.name
      )
    }

  def assertNoReferences(teamId: ObjectId)(implicit mp: MessagesProvider): Fox[Unit] =
    for {
      projectCount <- projectDAO.countForTeam(teamId)
      _ <- bool2Fox(projectCount == 0) ?~> Messages("team.inUse.projects", projectCount)
      taskTypeCount <- taskTypeDAO.countForTeam(teamId)
      _ <- bool2Fox(projectCount == 0) ?~> Messages("team.inUse.taskTypes", taskTypeCount)
      annotationCount <- annotationDAO.countForTeam(teamId)
      _ <- bool2Fox(projectCount == 0) ?~> Messages("team.inUse.annotations", annotationCount)
    } yield ()

}

class TeamDAO @Inject()(sqlClient: SQLClient)(implicit ec: ExecutionContext)
    extends SQLDAO[Team, TeamsRow, Teams](sqlClient) {
  val collection = Teams

  def idColumn(x: Teams): Rep[String] = x._Id
  def isDeletedColumn(x: Teams): Rep[Boolean] = x.isdeleted

  def parse(r: TeamsRow): Fox[Team] =
    Fox.successful(
      Team(
        ObjectId(r._Id),
        ObjectId(r._Organization),
        r.name,
        r.isorganizationteam,
        r.created.getTime,
        r.isdeleted
      ))

  override def readAccessQ(requestingUserId: ObjectId) =
    s"""(_id in (select _team from webknossos.user_team_roles where _user = '${requestingUserId.id}')
       or _organization in (select _organization from webknossos.users_ where _id = '${requestingUserId.id}' and isAdmin))"""

  override def deleteAccessQ(requestingUserId: ObjectId) =
    s"""(not isorganizationteam
          and _organization in (select _organization from webknossos.users_ where _id = '${requestingUserId.id}' and isAdmin))"""

  override def findOne(id: ObjectId)(implicit ctx: DBAccessContext): Fox[Team] =
    for {
      accessQuery <- readAccessQuery
      r <- run(sql"select #$columns from #$existingCollectionName where _id = ${id.id} and #$accessQuery".as[TeamsRow])
      parsed <- parseFirst(r, id)
    } yield parsed

  def countByNameAndOrganization(teamName: String, organizationId: ObjectId): Fox[Int] =
    for {
      countList <- run(
        sql"select count(_id) from #$existingCollectionName where name = $teamName and _organization = $organizationId"
          .as[Int])
      count <- countList.headOption
    } yield count

  override def findAll(implicit ctx: DBAccessContext): Fox[List[Team]] =
    for {
      accessQuery <- readAccessQuery
      r <- run(sql"select #$columns from #$existingCollectionName where #$accessQuery".as[TeamsRow])
      parsed <- parseAll(r)
    } yield parsed

  def findAllEditable(implicit ctx: DBAccessContext): Fox[List[Team]] =
    for {
      requestingUserId <- userIdFromCtx
      accessQuery <- readAccessQuery
      r <- run(sql"""select #$columns from #$existingCollectionName
                     where (_id in (select _team from webknossos.user_team_roles where _user = ${requestingUserId.id} and isTeamManager)
                           or _organization in (select _organization from webknossos.users_ where _id = ${requestingUserId.id} and isAdmin))
                     and #$accessQuery""".as[TeamsRow])
      parsed <- parseAll(r)
    } yield parsed

  def findAllByOrganization(organizationId: ObjectId)(implicit ctx: DBAccessContext): Fox[List[Team]] =
    for {
      accessQuery <- readAccessQuery
      r <- run(
        sql"select #$columns from #$existingCollectionName where _organization = ${organizationId.id} and #$accessQuery"
          .as[TeamsRow])
      parsed <- parseAll(r)
    } yield parsed

  def findAllIdsByOrganization(organizationId: ObjectId)(implicit ctx: DBAccessContext): Fox[List[ObjectId]] =
    for {
      accessQuery <- readAccessQuery
      r <- run(
        sql"select _id from #$existingCollectionName where _organization = ${organizationId.id} and #$accessQuery"
          .as[String])
      parsed <- Fox.serialCombined(r.toList)(col => ObjectId.fromString(col))
    } yield parsed

  def findAllForDataSet(dataSetId: ObjectId)(implicit ctx: DBAccessContext): Fox[List[Team]] =
    for {
      accessQuery <- readAccessQuery
      r <- run(sql"""select #${columnsWithPrefix("t.")} from #$existingCollectionName t
                     join webknossos.dataSet_allowedTeams at on t._id = at._team
                     where at._dataSet = $dataSetId and #$accessQuery""".as[TeamsRow])
      parsed <- parseAll(r)
    } yield parsed

  def findSharedTeamsForAnnotation(annotationId: ObjectId)(implicit ctx: DBAccessContext): Fox[List[Team]] =
    for {
      accessQuery <- readAccessQuery
      r <- run(sql"""select #$columns from #$existingCollectionName
              where _id in (select _team from webknossos.annotation_sharedTeams where _annotation = $annotationId)
              and #$accessQuery""".as[TeamsRow])
      parsed <- parseAll(r)
    } yield parsed

  def insertOne(t: Team): Fox[Unit] =
    for {
      _ <- run(sqlu"""insert into webknossos.teams(_id, _organization, name, created, isOrganizationTeam, isDeleted)
                  values(${t._id.id}, ${t._organization.id}, ${t.name}, ${new java.sql.Timestamp(t.created)}, ${t.isOrganizationTeam}, ${t.isDeleted})
            """)
    } yield ()

}
