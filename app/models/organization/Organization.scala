package models.organization

import com.scalableminds.util.accesscontext.DBAccessContext
import com.scalableminds.util.tools.Fox
import com.scalableminds.webknossos.schema.Tables._
import javax.inject.Inject
import models.team.PricingPlan
import models.team.PricingPlan.PricingPlan
import slick.jdbc.PostgresProfile.api._
import slick.lifted.Rep
import utils.{ObjectId, SQLClient, SQLDAO}

import scala.concurrent.ExecutionContext

case class Organization(
    _id: ObjectId,
    name: String,
    additionalInformation: String,
    logoUrl: String,
    displayName: String,
    pricingPlan: PricingPlan,
    newUserMailingList: String = "",
    overTimeMailingList: String = "",
    enableAutoVerify: Boolean = false,
    created: Long = System.currentTimeMillis(),
    isDeleted: Boolean = false
)

class OrganizationDAO @Inject()(sqlClient: SQLClient)(implicit ec: ExecutionContext)
    extends SQLDAO[Organization, OrganizationsRow, Organizations](sqlClient) {
  val collection = Organizations

  def idColumn(x: Organizations): Rep[String] = x._Id

  def isDeletedColumn(x: Organizations): Rep[Boolean] = x.isdeleted

  def parse(r: OrganizationsRow): Fox[Organization] =
    for {
      pricingPlan <- PricingPlan.fromString(r.pricingplan).toFox
    } yield {
      Organization(
        ObjectId(r._Id),
        r.name,
        r.additionalinformation,
        r.logourl,
        r.displayname,
        pricingPlan,
        r.newusermailinglist,
        r.overtimemailinglist,
        r.enableautoverify,
        r.created.getTime,
        r.isdeleted
      )
    }

  override def readAccessQ(requestingUserId: ObjectId): String =
    s"((_id in (select _organization from webknossos.users_ where _multiUser = (select _multiUser from webknossos.users_ where _id = '$requestingUserId')))" +
      s"or 'true' in (select isSuperUser from webknossos.multiUsers_ where _id in (select _multiUser from webknossos.users_ where _id = '$requestingUserId')))"

  override def anonymousReadAccessQ(sharingToken: Option[String]): String = sharingToken match {
    case Some(_) => "true"
    case _       => "false"
  }

  override def findAll(implicit ctx: DBAccessContext): Fox[List[Organization]] =
    for {
      accessQuery <- readAccessQuery
      r <- run(sql"select #$columns from #$existingCollectionName where #$accessQuery".as[OrganizationsRow])
      parsed <- parseAll(r)
    } yield parsed

  def findOneByName(name: String)(implicit ctx: DBAccessContext): Fox[Organization] =
    for {
      accessQuery <- readAccessQuery
      r <- run(
        sql"select #$columns from #$existingCollectionName where name = $name and #$accessQuery".as[OrganizationsRow])
      parsed <- parseFirst(r, name)
    } yield parsed

  def insertOne(o: Organization): Fox[Unit] =
    for {
      _ <- run(
        sqlu"""insert into webknossos.organizations(_id, name, additionalInformation, logoUrl, displayName, newUserMailingList, overTimeMailingList, enableAutoVerify, created, isDeleted)
                  values(${o._id.id}, ${o.name}, ${o.additionalInformation}, ${o.logoUrl}, ${o.displayName}, ${o.newUserMailingList}, ${o.overTimeMailingList}, ${o.enableAutoVerify}, ${new java.sql.Timestamp(
          o.created)}, ${o.isDeleted})
            """)
    } yield ()

  def findOrganizationTeamId(o: ObjectId): Fox[ObjectId] =
    for {
      rList <- run(sql"select _id from webknossos.organizationTeams where _organization = ${o.id}".as[String])
      r <- rList.headOption.toFox
      parsed <- ObjectId.fromString(r)
    } yield parsed

  def findOrganizationNameForAnnotation(annotationId: ObjectId): Fox[String] =
    for {
      rList <- run(sql"""select o.name
              from webknossos.annotations_ a
              join webknossos.datasets_ d on a._dataSet = d._id
              join webknossos.organizations_ o on d._organization = o._id
              where a._id = $annotationId""".as[String])
      r <- rList.headOption.toFox
    } yield r

  def updateFields(organizationId: ObjectId, displayName: String, newUserMailingList: String)(
      implicit ctx: DBAccessContext): Fox[Unit] =
    for {
      _ <- assertUpdateAccess(organizationId)
      _ <- run(sqlu"""update webknossos.organizations
                      set displayName = $displayName, newUserMailingList = $newUserMailingList
                      where _id = $organizationId""")
    } yield ()

}
