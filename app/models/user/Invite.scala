package models.user

import akka.actor.ActorSystem
import com.scalableminds.util.accesscontext.DBAccessContext
import com.scalableminds.util.tools.{Fox, FoxImplicits}
import com.scalableminds.webknossos.schema.Tables._
import com.typesafe.scalalogging.LazyLogging
import javax.inject.Inject
import models.organization.OrganizationDAO
import org.joda.time.DateTime
import oxalis.mail.{DefaultMails, Send}
import oxalis.security.RandomIDGenerator
import slick.jdbc.PostgresProfile.api._
import slick.lifted.Rep
import utils.{ObjectId, SQLClient, SQLDAO, WkConf}

import scala.concurrent.{ExecutionContext, Future}

case class Invite(
    _id: ObjectId,
    tokenValue: String,
    _organization: ObjectId,
    autoActivate: Boolean,
    expirationDateTime: DateTime,
    created: Long = System.currentTimeMillis(),
    isDeleted: Boolean = false
)

class InviteService @Inject()(conf: WkConf,
                              actorSystem: ActorSystem,
                              defaultMails: DefaultMails,
                              organizationDAO: OrganizationDAO,
                              inviteDAO: InviteDAO)(implicit ec: ExecutionContext)
    extends FoxImplicits
    with LazyLogging {
  private val tokenValueGenerator = new RandomIDGenerator
  private lazy val Mailer =
    actorSystem.actorSelection("/user/mailActor")

  def inviteOneRecipient(recipient: String, sender: User, autoActivate: Boolean)(
      implicit ctx: DBAccessContext): Fox[Unit] =
    for {
      invite <- generateInvite(sender._organization, autoActivate)
      _ <- inviteDAO.insertOne(invite)
      _ <- sendInviteMail(recipient, sender, invite)
    } yield ()

  private def generateInvite(organizationID: ObjectId, autoActivate: Boolean): Future[Invite] =
    for {
      tokenValue <- tokenValueGenerator.generate
    } yield
      Invite(
        ObjectId.generate,
        tokenValue,
        organizationID,
        autoActivate,
        new DateTime(System.currentTimeMillis() + conf.WebKnossos.User.inviteExpiry.toMillis)
      )

  private def sendInviteMail(recipient: String, sender: User, invite: Invite)(
      implicit ctx: DBAccessContext): Fox[Unit] =
    for {
      organization <- organizationDAO.findOne(invite._organization)
      _ = logger.info("sending invite mail")
      _ = Mailer ! Send(
        defaultMails
          .inviteMail(recipient, invite.tokenValue, invite.autoActivate, organization.displayName, sender.name))
    } yield ()

  def removeExpiredInvites(): Fox[Unit] =
    inviteDAO.deleteAllExpired()

  def deactivateUsedInvite(invite: Invite)(implicit ctx: DBAccessContext): Fox[Unit] =
    inviteDAO.deleteOne(invite._id)

  def findInviteByTokenOpt(tokenValueOpt: Option[String]): Fox[Invite] =
    tokenValueOpt match {
      case Some(tokenValue) => inviteDAO.findOneByTokenValue(tokenValue)
      case None             => Fox.failure("No invite Token")
    }

}

class InviteDAO @Inject()(sqlClient: SQLClient)(implicit ec: ExecutionContext)
    extends SQLDAO[Invite, InvitesRow, Invites](sqlClient) {
  val collection = Invites

  def idColumn(x: Invites): Rep[String] = x._Id

  def isDeletedColumn(x: Invites): Rep[Boolean] = x.isdeleted

  def parse(r: InvitesRow): Fox[Invite] =
    Fox.successful(
      Invite(
        ObjectId(r._Id),
        r.tokenvalue,
        ObjectId(r._Organization),
        r.autoactivate,
        new DateTime(r.expirationdatetime.getTime),
        r.created.getTime,
        r.isdeleted
      ))

  def findOneByTokenValue(tokenValue: String): Fox[Invite] =
    for {
      rOpt <- run(Invites.filter(r => notdel(r) && r.tokenvalue === tokenValue).result.headOption)
      r <- rOpt.toFox
      parsed <- parse(r)
    } yield parsed

  def insertOne(i: Invite): Fox[Unit] =
    for {
      _ <- run(
        sqlu"""insert into webknossos.invites(_id, tokenValue, _organization, autoActivate, expirationDateTime, created, isDeleted)
                    values(${i._id}, ${i.tokenValue}, ${i._organization}, ${i.autoActivate},
                    ${new java.sql.Timestamp(i.expirationDateTime.getMillis)}, ${new java.sql.Timestamp(i.created)}, ${i.isDeleted})""")
    } yield ()

  def deleteAllExpired(): Fox[Unit] = {
    val q = for {
      row <- collection if notdel(row) && row.expirationdatetime <= new java.sql.Timestamp(System.currentTimeMillis)
    } yield isDeletedColumn(row)
    for { _ <- run(q.update(true)) } yield ()
  }

}
