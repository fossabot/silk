package controllers.transform

import controllers.core.{Stream, Widgets}
import de.fuberlin.wiwiss.silk.config.TransformSpecification
import de.fuberlin.wiwiss.silk.dataset.Dataset
import de.fuberlin.wiwiss.silk.workspace.User
import models.transform.CurrentExecuteTransformTask
import play.api.mvc.{Action, Controller}
import plugins.Context

object ExecuteTransformTab extends Controller {

  def execute(project: String, task: String) = Action { request =>
    val context = Context.get[TransformSpecification](project, task, request.path)
    Ok(views.html.executeTransform.executeTransform(context))
  }

  def executeDialog(projectName: String, taskName: String) = Action {
    val project = User().workspace.project(projectName)
    val outputs = project.tasks[Dataset].toSeq.map(_.name.toString())

    Ok(views.html.executeTransform.executeTransformDialog(projectName, taskName, outputs))
  }

  def statusStream(project: String, task: String) = Action {
    val stream = Stream.currentStatus(CurrentExecuteTransformTask)
    Ok.chunked(Widgets.status(stream))
  }

}