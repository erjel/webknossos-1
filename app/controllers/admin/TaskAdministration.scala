package controllers.admin

import scala.Array.canBuildFrom
import scala.Option.option2Iterable
import brainflight.security.AuthenticatedRequest
import brainflight.security.Secured
import brainflight.tools.ExtendedTypes.String2ExtendedString
import brainflight.tools.geometry.Point3D
import models.binary.DataSet
import models.security.Role
import models.task.Experiment
import models.task.Task
import models.task.TaskType
import play.api.data.Form
import play.api.data.Forms.mapping
import play.api.data.Forms.number
import play.api.data.Forms.text
import views.html
import models.user.Experience
import controllers.Controller
import play.api.i18n.Messages

object TaskAdministration extends Controller with Secured {

  override val DefaultAccessRole = Role.Admin

  val taskFromExperimentForm = Form(
    mapping(
      "experiment" -> text.verifying("experiment.invalid", experiment => Experiment.findOneById(experiment).isDefined),
      "taskType" -> text.verifying("taskType.invalid", task => TaskType.findOneById(task).isDefined),
      "experience" -> mapping(
        "domain" -> text,
        "value" -> number)(Experience.apply)(Experience.unapply),
      "priority" -> number,
      "taskInstances" -> number)(Task.fromExperimentForm)(Task.toExperimentForm)).fill(Task.empty)

  val taskMapping = mapping(
    "dataSet" -> text.verifying("dataSet.invalid", name => DataSet.findOneByName(name).isDefined),
    "taskType" -> text.verifying("taskType.invalid", task => TaskType.findOneById(task).isDefined),
    "start" -> mapping(
      "point" -> text.verifying("point.invalid", p => p.matches("([0-9]+),\\s*([0-9]+),\\s*([0-9]+)\\s*")))(Point3D.fromForm)(Point3D.toForm),
    "experience" -> mapping(
      "domain" -> text,
      "value" -> number)(Experience.apply)(Experience.unapply),
    "priority" -> number,
    "taskInstances" -> number)(Task.fromForm)(Task.toForm)

  val taskForm = Form(
    taskMapping).fill(Task.empty)

  def list = Authenticated { implicit request =>
    Ok(html.admin.task.taskList(request.user, Task.findAllNonTrainings))
  }

  def taskCreateHTML(experimentForm: Form[models.task.Task], taskForm: Form[models.task.Task])(implicit request: AuthenticatedRequest[_]) =
    html.admin.task.taskCreate(request.user,
      Experiment.findAllExploratory,
      TaskType.findAll,
      DataSet.findAll,
      Experience.findAllDomains,
      experimentForm,
      taskForm)

  def create = Authenticated { implicit request =>
    Ok(taskCreateHTML(taskForm, taskFromExperimentForm))
  }

  def delete(taskId: String) = Authenticated { implicit request =>
    Task.findOneById(taskId).map { task =>
      Task.remove(task)
      AjaxOk.success(Messages("task.removed"))
    } getOrElse AjaxBadRequest.error("Task couldn't get removed (task not found)")

  }

  def createFromForm = Authenticated(parser = parse.urlFormEncoded) { implicit request =>
    taskForm.bindFromRequest.fold(
      formWithErrors => BadRequest(taskCreateHTML(taskFromExperimentForm, formWithErrors)),
      { t =>
        Task.insert(t)
        Redirect(routes.TaskAdministration.list)
      })
  }

  def createFromExperiment = Authenticated(parser = parse.urlFormEncoded) { implicit request =>
    taskFromExperimentForm.bindFromRequest.fold(
      formWithErrors => BadRequest(taskCreateHTML(formWithErrors, taskForm)),
      { t =>
        Task.insert(t)
        Redirect(routes.TaskAdministration.list)
      })
  }

  def createBulk = Authenticated(parser = parse.urlFormEncoded) { implicit request =>
    request.request.body.get("data").flatMap(_.headOption).map { data =>
      val inserted = data
        .split("\n")
        .map(_.split(" "))
        .filter(_.size == 9)
        .flatMap { params =>
          for {
            experienceValue <- params(3).toIntOpt
            x <- params(4).toIntOpt
            y <- params(5).toIntOpt
            z <- params(6).toIntOpt
            priority <- params(7).toIntOpt
            instances <- params(8).toIntOpt
            taskTypeSummary = params(1)
            taskType <- TaskType.findOneBySumnary(taskTypeSummary)
          } yield {
            val dataSetName = params(0)
            val experience = Experience(params(2), experienceValue)
            Task(dataSetName, 0, taskType._id, Point3D(x, y, z), experience, priority, instances)
          }
        }
        .flatMap { t =>
          Task.insert(t)
        }
      Redirect(routes.TaskAdministration.list)
    } getOrElse BadRequest("'data' parameter is mising")
  }
}