package controllers.api

import play.api.mvc.Action
import play.api.libs.json.{JsString, JsObject, JsArray, JsValue}
import de.fuberlin.wiwiss.silk.workspace.User
import de.fuberlin.wiwiss.silk.datasource.DataSource
import de.fuberlin.wiwiss.silk.output.LinkWriter
import play.api.mvc.Controller
import de.fuberlin.wiwiss.silk.workspace.modules.source.SourceTask
import de.fuberlin.wiwiss.silk.datasource.Source
import de.fuberlin.wiwiss.silk.execution.OutputTask
import de.fuberlin.wiwiss.silk.util.DPair
import de.fuberlin.wiwiss.silk.workspace.modules.output.OutputTask
import de.fuberlin.wiwiss.silk.output.Output
import de.fuberlin.wiwiss.silk.workbench.Constants
import de.fuberlin.wiwiss.silk.entity.SparqlRestriction
import de.fuberlin.wiwiss.silk.linkagerule.LinkageRule
import de.fuberlin.wiwiss.silk.workspace.modules.linking.LinkingTask
import de.fuberlin.wiwiss.silk.evaluation.ReferenceLinks
import de.fuberlin.wiwiss.silk.workspace.io.{SilkConfigImporter, ProjectImporter, ProjectExporter}
import de.fuberlin.wiwiss.silk.config._

object WorkspaceApi extends Controller {
  
  def workspace = Action {
    // TODO - Nested 'yield's seem to cause the (strange) compiler error: 'xxx is not an enclosing class'
    var projectList: List[JsValue] = List()

    for (project <- User().workspace.projects.toSeq.sortBy(n => (n.name.toString.toLowerCase))) {
      implicit val prefixes = project.config.prefixes

      val sources = JsArray(
        for (task <- project.sourceModule.tasks.toList.sortBy(n => (n.name.toString.toLowerCase))) yield {
          task.source.dataSource match {
            case DataSource(_, params) => JsObject(Seq(
              ("name" -> JsString(task.name.toString)),
              ("params" -> paramsToJson(params))
            ))
          }
        }
      )

      val linkingTasks = JsArray(
        for (task <- project.linkingModule.tasks.toList.sortBy(n => (n.name.toString.toLowerCase))) yield {
          JsObject(Seq(
                    ("name" -> JsString(task.name.toString)),
                    ("source" -> JsString(task.linkSpec.datasets.source.sourceId.toString)),
                    ("target" -> JsString(task.linkSpec.datasets.target.sourceId.toString)),
                    ("sourceDataset" -> JsString(task.linkSpec.datasets.source.restriction.toString)),
                    ("targetDataset" -> JsString(task.linkSpec.datasets.target.restriction.toString)),
                    ("linkType" -> JsString(task.linkSpec.linkType.toTurtle))
                  ))
        }
      )

      val outputs = JsArray(
        for (task <- project.outputModule.tasks.toList.sortBy(n => (n.name.toString.toLowerCase))) yield {
          task.output.writer match {
            case LinkWriter(_, params) => JsObject(Seq(
              ("name" -> JsString(task.name.toString)),
              ("params" -> paramsToJson(params))
            ))
          }
        }
      )

      val proj = JsObject(Seq(
        ("name" -> JsString(project.name.toString)),
        ("dataSource" -> sources),
        ("linkingTask" -> linkingTasks),
        ("output" -> outputs)
      ))

      projectList :+= proj
    }

    val projects = ("project" -> JsArray(projectList))
    val activeProject = ("activeProject" -> JsString(if (User().projectOpen) User().project.name.toString else ""))
    val activeTask = ("activeTask" -> JsString(if (User().taskOpen) User().task.name.toString else ""))
    val activeTaskType = ("activeTaskType" -> JsString(if (User().taskOpen) User().task.getClass.getSimpleName else ""))

    val workspaceJson = JsObject(Seq("workspace" -> JsObject(Seq(projects,activeProject,activeTask,activeTaskType))))

    Ok(workspaceJson)
  }

  private def paramsToJson(params: Map[String, String]) = {
    JsArray(
      for ((key, value) <- params.toList) yield {
        JsObject(Seq(
          ("key" -> JsString(key)),
          ("value" -> JsString(value))
        ))
      }
    )
  }
  
  def newProject(project: String) = Action {
    User().workspace.createProject(project)
    Ok
  }

  def deleteProject(project: String) = Action {
    User().workspace.removeProject(project)
    Ok
  }

  def importProject(project: String) = Action { implicit request => {
    for(data <- request.body.asMultipartFormData;
        file <- data.files) {
      ProjectImporter(User().workspace.createProject(project), scala.xml.XML.loadFile(file.ref.file))
    }
    Ok
  }}

  def exportProject(project: String) = Action {
    val xml = ProjectExporter(User().workspace.project(project))
    Ok(xml).withHeaders("Content-Disposition" -> "attachment")
  }

  def importLinkSpec(projectName: String) = Action { implicit request => {
    val project = User().workspace.project(projectName)

    for(data <- request.body.asMultipartFormData;
        file <- data.files) {
      val config = LinkingConfig.fromXML(scala.xml.XML.loadFile(file.ref.file))
      SilkConfigImporter(config, project)
    }
    Ok
  }}

  def updatePrefixes(project: String) = Action { implicit request => {
    val prefixMap = request.body.asFormUrlEncoded.getOrElse(Map.empty).mapValues(_.mkString)
    val projectObj = User().workspace.project(project)
    projectObj.config = projectObj.config.copy(prefixes = Prefixes(prefixMap))

    Ok
  }}

  def putSource(project: String, source: String) = Action { implicit request => {
    request.body.asXml match {
      case Some(xml) => {
        try {
          val sourceTask = SourceTask(Source.fromXML(xml.head))
          User().workspace.project(project).sourceModule.update(sourceTask)
          Ok
        } catch {
          case ex: Exception => BadRequest(ex.getMessage)
        }
      }
      case None => BadRequest("Expecting text/xml request body")
    }
  }}

  def deleteSource(project: String, source: String) = Action {
    User().workspace.project(project).sourceModule.remove(source)
    Ok
  }

  def putLinkingTask(project: String, task: String) = Action { implicit request => {
    val values = request.body.asFormUrlEncoded.getOrElse(Map.empty).mapValues(_.mkString)

    val proj = User().workspace.project(project)
    implicit val prefixes = proj.config.prefixes

    val datasets = DPair(Dataset(values("source"), Constants.SourceVariable, SparqlRestriction.fromSparql(Constants.SourceVariable, values("sourcerestriction"))),
                         Dataset(values("target"), Constants.TargetVariable, SparqlRestriction.fromSparql(Constants.TargetVariable, values("targetrestriction"))))

    proj.linkingModule.tasks.find(_.name == task) match {
      //Update existing task
      case Some(oldTask) => {
        val updatedLinkSpec = oldTask.linkSpec.copy(datasets = datasets, linkType = values("linktype"))
        val updatedLinkingTask = oldTask.updateLinkSpec(updatedLinkSpec, proj)
        proj.linkingModule.update(updatedLinkingTask)
      }
      //Create new task
      case None => {
        val linkSpec =
          LinkSpecification(
            id = task,
            linkType = values("linktype"),
            datasets = datasets,
            rule = LinkageRule(None),
            filter = LinkFilter(),
            outputs = Nil
          )

        val linkingTask = LinkingTask(proj, linkSpec, ReferenceLinks())
        proj.linkingModule.update(linkingTask)
      }
    }
    Ok
  }}

  def deleteLinkingTask(project: String, task: String) = Action {
    User().workspace.project(project).linkingModule.remove(task)
    Ok
  }

  def putOutput(project: String, output: String) = Action { implicit request => {
    request.body.asXml match {
      case Some(xml) => {
        try {
          val outputTask = OutputTask(Output.fromXML(xml.head))
          User().workspace.project(project).outputModule.update(outputTask)
          Ok
        } catch {
          case ex: Exception => BadRequest(ex.getMessage)
        }
      }
      case None => BadRequest("Expecting text/xml request body")
    }
  }}

  def deleteOutput(project: String, output: String) = Action {
    User().workspace.project(project).outputModule.remove(output)
    Ok
  }
}