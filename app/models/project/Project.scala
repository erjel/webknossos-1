package models.project

import com.scalableminds.util.accesscontext.{DBAccessContext, GlobalAccessContext}
import com.scalableminds.util.tools.{Fox, FoxImplicits}
import com.scalableminds.webknossos.schema.Tables._
import com.typesafe.scalalogging.LazyLogging
import javax.inject.Inject
import models.annotation.{AnnotationState, AnnotationType}
import models.task.TaskDAO
import models.team.TeamDAO
import models.user.{User, UserService}
import net.liftweb.common.Full
import play.api.libs.functional.syntax._
import play.api.libs.json.{Json, _}
import slick.jdbc.PostgresProfile.api._
import slick.lifted.Rep
import utils.{ObjectId, SQLClient, SQLDAO}

import scala.concurrent.{ExecutionContext, Future}

case class Project(
    _id: ObjectId,
    _team: ObjectId,
    _owner: ObjectId,
    name: String,
    priority: Long,
    paused: Boolean,
    expectedTime: Option[Long],
    isBlacklistedFromReport: Boolean,
    created: Long = System.currentTimeMillis(),
    isDeleted: Boolean = false
) extends FoxImplicits {

  def isDeletableBy(user: User): Boolean = user._id == _owner || user.isAdmin

}

object Project {
  private val validateProjectName = Reads.pattern("^[a-zA-Z0-9_-]*$".r, "project.name.invalidChars")

  // format: off
  val projectPublicReads: Reads[Project] =
    ((__ \ 'name).read[String](Reads.minLength[String](3) keepAnd validateProjectName) and
      (__ \ 'team).read[ObjectId] and
      (__ \ 'priority).read[Int] and
      (__ \ 'paused).readNullable[Boolean] and
      (__ \ 'expectedTime).readNullable[Long] and
      (__ \ 'owner).read[ObjectId] and
      (__ \ 'isBlacklistedFromReport).read[Boolean]) (
      (name, team, priority, paused, expectedTime, owner, isBlacklistedFromReport) =>
        Project(ObjectId.generate, team, owner, name, priority, paused getOrElse false, expectedTime, isBlacklistedFromReport))
  // format: on

}

class ProjectDAO @Inject()(sqlClient: SQLClient)(implicit ec: ExecutionContext)
    extends SQLDAO[Project, ProjectsRow, Projects](sqlClient) {
  val collection = Projects

  def idColumn(x: Projects): Rep[String] = x._Id
  def isDeletedColumn(x: Projects): Rep[Boolean] = x.isdeleted

  def parse(r: ProjectsRow): Fox[Project] =
    Fox.successful(
      Project(
        ObjectId(r._Id),
        ObjectId(r._Team),
        ObjectId(r._Owner),
        r.name,
        r.priority,
        r.paused,
        r.expectedtime,
        r.isblacklistedfromreport,
        r.created.getTime,
        r.isdeleted
      ))

  override def readAccessQ(requestingUserId: ObjectId) =
    s"""(
        (_team in (select _team from webknossos.user_team_roles where _user = '${requestingUserId.id}'))
        or _owner = '${requestingUserId.id}'
        or _organization = (select _organization from webknossos.users_ where _id = '${requestingUserId.id}' and isAdmin)
        )"""
  override def deleteAccessQ(requestingUserId: ObjectId) = s"_owner = '${requestingUserId.id}'"

  // read operations

  override def findOne(id: ObjectId)(implicit ctx: DBAccessContext): Fox[Project] =
    for {
      accessQuery <- readAccessQuery
      r <- run(sql"select #$columns from #$existingCollectionName where _id = $id and #$accessQuery".as[ProjectsRow])
      parsed <- parseFirst(r, id)
    } yield parsed

  override def findAll(implicit ctx: DBAccessContext): Fox[List[Project]] =
    for {
      accessQuery <- readAccessQuery
      r <- run(sql"select #$columns from #$existingCollectionName where #$accessQuery order by created".as[ProjectsRow])
      parsed <- parseAll(r)
    } yield parsed

  // Does not use access query (because they dont support prefixes). Use only after separate access check!
  def findAllWithTaskType(taskTypeId: String): Fox[List[Project]] =
    for {
      r <- run(
        sql"""select distinct #${columnsWithPrefix("p.")}
              from webknossos.projects_ p
              join webknossos.tasks_ t on t._project = p._id
              join webknossos.taskTypes_ tt on t._taskType = tt._id
              where tt._id = $taskTypeId
           """.as[ProjectsRow]
      )
      parsed <- parseAll(r)
    } yield parsed

  def findOneByNameAndOrganization(name: String, organizationId: ObjectId)(
      implicit ctx: DBAccessContext): Fox[Project] =
    for {
      accessQuery <- readAccessQuery
      r <- run(
        sql"select #$columns from #$existingCollectionName where name = '#${sanitize(name)}' and _organization = $organizationId and #$accessQuery"
          .as[ProjectsRow])
      parsed <- parseFirst(r, s"$organizationId/$name")
    } yield parsed

  def findUsersWithActiveTasks(projectId: ObjectId): Fox[List[(String, String, String, Int)]] =
    for {
      rSeq <- run(sql"""select m.email, u.firstName, u.lastName, count(a._id)
                         from
                         webknossos.annotations_ a
                         join webknossos.tasks_ t on a._task = t._id
                         join webknossos.projects_ p on t._project = p._id
                         join webknossos.users_ u on a._user = u._id
                         join webknossos.multiusers_ m on u._multiUser = m._id
                         where p._id = $projectId
                         and a.state = '#${AnnotationState.Active.toString}'
                         and a.typ = '#${AnnotationType.Task}'
                         group by m.email, u.firstName, u.lastName
                     """.as[(String, String, String, Int)])
    } yield rSeq.toList

  // write operations

  def insertOne(p: Project, organizationId: ObjectId): Fox[Unit] =
    for {
      _ <- run(sqlu"""insert into webknossos.projects(
                                     _id, _organization, _team, _owner, name, priority,
                                     paused, expectedTime, isblacklistedfromreport, created, isDeleted)
                         values(${p._id}, $organizationId, ${p._team}, ${p._owner}, ${p.name}, ${p.priority},
                         ${p.paused}, ${p.expectedTime}, ${p.isBlacklistedFromReport},
                         ${new java.sql.Timestamp(p.created)}, ${p.isDeleted})""")
    } yield ()

  def updateOne(p: Project)(implicit ctx: DBAccessContext): Fox[Unit] =
    for { // note that p.created is immutable, hence skipped here
      _ <- assertUpdateAccess(p._id)
      _ <- run(sqlu"""update webknossos.projects
                          set
                            _team = ${p._team.id},
                            _owner = ${p._owner.id},
                            name = ${p.name},
                            priority = ${p.priority},
                            paused = ${p.paused},
                            expectedTime = ${p.expectedTime},
                            isblacklistedfromreport = ${p.isBlacklistedFromReport},
                            isDeleted = ${p.isDeleted}
                          where _id = ${p._id}""")
    } yield ()

  def updatePaused(id: ObjectId, isPaused: Boolean)(implicit ctx: DBAccessContext): Fox[Unit] =
    updateBooleanCol(id, _.paused, isPaused)

  def countForTeam(teamId: ObjectId): Fox[Int] =
    for {
      countList <- run(sql"select count(_id) from #$existingCollectionName where _team = $teamId".as[Int])
      count <- countList.headOption
    } yield count

}

class ProjectService @Inject()(projectDAO: ProjectDAO, teamDAO: TeamDAO, userService: UserService, taskDAO: TaskDAO)(
    implicit ec: ExecutionContext)
    extends LazyLogging
    with FoxImplicits {

  def deleteOne(projectId: ObjectId)(implicit ctx: DBAccessContext): Fox[Boolean] = {
    val futureFox: Future[Fox[Boolean]] = for {
      removalSuccessBox <- projectDAO.deleteOne(projectId).futureBox
    } yield {
      removalSuccessBox match {
        case Full(_) =>
          for {
            _ <- taskDAO.removeAllWithProjectAndItsAnnotations(projectId)
            _ = logger.info(s"Project $projectId was deleted.")
          } yield true
        case _ =>
          logger.warn(s"Tried to remove project $projectId without permission.")
          Fox.successful(false)
      }
    }
    futureFox.toFox.flatten
  }

  def publicWrites(project: Project)(implicit ctx: DBAccessContext): Fox[JsObject] =
    for {
      owner <- userService
        .findOneById(project._owner, useCache = true)
        .flatMap(u => userService.compactWrites(u))
        .futureBox
      teamNameOpt <- teamDAO.findOne(project._team)(GlobalAccessContext).map(_.name).toFutureOption
    } yield {
      Json.obj(
        "name" -> project.name,
        "team" -> project._team.toString,
        "teamName" -> teamNameOpt,
        "owner" -> owner.toOption,
        "priority" -> project.priority,
        "paused" -> project.paused,
        "expectedTime" -> project.expectedTime,
        "isBlacklistedFromReport" -> project.isBlacklistedFromReport,
        "id" -> project._id.toString,
        "created" -> project.created
      )
    }

  def publicWritesWithStatus(project: Project, openTaskInstances: Long, tracingTime: Long)(
      implicit ctx: DBAccessContext): Fox[JsObject] =
    for {
      projectJson <- publicWrites(project)
    } yield {
      projectJson ++ Json.obj("numberOfOpenAssignments" -> JsNumber(openTaskInstances), "tracingTime" -> tracingTime)
    }

}
