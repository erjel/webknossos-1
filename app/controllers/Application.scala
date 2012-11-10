package controllers

import play.api._
import play.api.mvc._
import play.api.data._
import play.api.Play.current
import play.api.libs.concurrent._
import models.user._
import views._
import play.mvc.Results.Redirect
import play.api.data.Forms._
import models._
import play.api.libs.iteratee.Done
import play.api.libs.iteratee.Input
import brainflight.security.Secured
import brainflight.mail._
import controllers.admin._
import play.api.libs.concurrent.Akka
import akka.actor.Props
import models.task.Experiment
import models.task.UsedExperiments
import brainflight.thirdparty.BrainTracing

object Application extends Controller with Secured {

  // -- Authentication

  val Mailer = Akka.system.actorOf(Props[Mailer], name = "mailActor")
  val autoVerify = Play.configuration.getBoolean("application.enableAutoVerify") getOrElse false

  val registerForm: Form[(String, String, String, String)] = {
    def registerFormApply(user: String, firstName: String, lastName: String, password: Tuple2[String, String]) =
      (user, firstName, lastName, password._1)
    def registerFormUnapply(user: (String, String, String, String)) =
      Some((user._1, user._2, user._3, ("", "")))

    val passwordField = tuple("main" -> text, "validation" -> text)
      .verifying("error.password.nomatch", pw => pw._1 == pw._2)
      .verifying("error.password.tooshort", pw => pw._1.length >= 6)

    Form(
      mapping(
        "email" -> email,
        "firstName" -> nonEmptyText(1, 30),
        "lastName" -> nonEmptyText(1, 30),
        "password" -> passwordField)(registerFormApply)(registerFormUnapply)
        .verifying("error.email.inuse",
          user => User.findLocalByEmail(user._1).isEmpty))
  }

  def register = Action {
    implicit request =>
      Ok(html.user.register(registerForm))
  }

  /**
   * Handle registration form submission.
   */
  def registrate = Action { implicit request =>
    Async {
      registerForm.bindFromRequest.fold(
        formWithErrors =>
          Promise.pure(BadRequest(html.user.register(formWithErrors))),
        {
          case (email, firstName, lastName, password) => {
            val user =
              if(autoVerify)
                User.alterAndSave(User(
                  email,
                  firstName,
                  lastName,
                  true,
                  brainflight.security.SCrypt.hashPassword(password),
                  "local",
                  UserConfiguration.defaultConfiguration,
                  Set("user")))
              else
                User.create(email, firstName, lastName, password)
                
            BrainTracing.register(user, password).map { brainDBresult =>
              Mailer ! Send(DefaultMails.registerMail(user.name, email, brainDBresult))
              Redirect(routes.Game.index)
                .withSession(Secured.createSession(user))
            }.asPromise
          }
        })
    }
  }

  val loginForm = Form(
    tuple(
      "email" -> text,
      "password" -> text) verifying ("error.login.invalid", result => result match {
        case (email, password) =>
          User.auth(email, password).isDefined
      }))

  /**
   * Login page.
   */
  def login = Action {
    implicit request =>
      Ok(html.user.login(loginForm))
  }

  /**
   * Handle login form submission.
   */
  def authenticate = Action {
    implicit request =>
      loginForm.bindFromRequest.fold(
        formWithErrors =>
          BadRequest(html.user.login(formWithErrors)),
        userForm => {
          val user = User.findLocalByEmail(userForm._1).get
          Redirect(routes.Game.index)
            .withSession(Secured.createSession(user))
        })
  }

  /**
   * Logout and clean the session.
   */
  def logout = Action {
    Redirect(routes.Application.login)
      .withNewSession
      .flashing("success" -> "You've been logged out")
  }

  // -- Javascript routing

  def javascriptRoutes = Action { implicit request =>
    Ok(
      Routes.javascriptRouter("jsRoutes")( //fill in stuff which should be able to be called from js
        controllers.routes.javascript.TaskController.finish,
        controllers.admin.routes.javascript.NMLIO.upload,
        controllers.admin.routes.javascript.NMLIO.downloadList)).as("text/javascript")
  }

}

