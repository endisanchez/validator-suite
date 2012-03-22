package controllers

import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.libs.iteratee._
import play.api.libs.concurrent._
import akka.dispatch.Future
import java.util.concurrent.TimeUnit._
import org.w3.util._
import org.w3.util.Pimps._
import org.w3.vs.controllers._
import org.w3.vs.exception._
import org.w3.vs.model._
import org.w3.vs.actor._
import org.w3.vs.actor.message._
import scalaz._
import Scalaz._
import Validation._
import akka.dispatch.Await
import akka.util.Duration
import play.api.data.Form

object Dashboard extends Controller {

  val logger = play.Logger.of("Controller.Dashboard")

  // TODO: make the implicit explicit!!!
  import org.w3.vs.Prod.configuration

  def index = Action { _ => Redirect(routes.Dashboard.dashboard) }

  def dashboard = Action { implicit req =>
    AsyncResult {
      (for {
        user <- getAuthenticatedUser
        jobConfs <- Job.getAll(user.organization)
      } yield {
        val jobDatas = jobConfs map { jobConf => Jobs.getJobOrCreate(jobConf).jobData }
        val result = Future.sequence(jobDatas).asPromise orTimeout (Timeout(new Throwable), 1, SECONDS) // validation in scalaz.syntax.ValidationV -> fail[X](x): Validation[Future,X]
        result map { either =>
          either.fold[Result](
            data => Ok(views.html.dashboard(jobConfs zip data)),
            timeout => failWithGrace(timeout))
        }
      }).fold(
        e => Promise.pure(failWithGrace(e)),
        s => s
      )
    }
  }

  // * Jobs
  def jobDispatcher(implicit id: JobId) = Action { implicit req =>
    (for {
      body <- req.body.asFormUrlEncoded
      param <- body.get("action")
      action <- param.headOption
    } yield action.toLowerCase match {
      case "delete" => deleteJob(id)(req)
      case "update" => createOrUpdateJob(Some(id))(req)
      case "on" => onJob(id)(req)
      case "off" => offJob(id)(req)
      case "stop" => stopJob(id)(req)
      case "refresh" => refreshJob(id)(req)
      case _ => BadRequest("BadRequest: unknown action")
    }).getOrElse(BadRequest("BadRequest: JobDispatcher"))
  }

  def showReport(implicit id: JobId) = Action { implicit req =>
    (for {
      user <- getAuthenticatedUser
      jobC <- getJobConfIfAllowed(user, id)
      ars <- Job.getAssertorResults(jobC.id)
    } yield Ok(views.html.job(Some(jobC), Some(ars)))).fold(
      e => failWithGrace(e),
      s => s
    )
  }

  def newJob() = newOrEditJob(None) 
  
  def editJob(implicit id: JobId) = newOrEditJob(Some(id))
  
  def newOrEditJob(implicit idOpt: Option[JobId]) = Action { implicit req =>
    (for {
      user <- getAuthenticatedUser failMap failWithGrace
      id <- idOpt toSuccess Ok(views.html.jobForm(jobForm))
      jobC <- getJobConfIfAllowed(user, id) failMap failWithGrace
    } yield Ok(views.html.jobForm(jobForm.fill(jobC)))).fold(f => f, s => s)
  }

  def deleteJob(implicit id: JobId) = Action { implicit req =>
    (for {
      user <- getAuthenticatedUser
      job <- getJobIfAllowed(user, id)
      _ <- Job.delete(id)
    } yield seeDashboard(Ok, ("info" -> "Job deleted"))).fold(
      e => failWithGrace(e),
      s => s
    )
  }
  
  def createJob = createOrUpdateJob(None) 

  def createOrUpdateJob(implicit idOpt: Option[JobId]) = Action { implicit req =>
    (for {
      user <- getAuthenticatedUser failMap failWithGrace
      jobF <- isValidForm(jobForm) failMap { formWithErrors => BadRequest(views.html.jobForm(formWithErrors)) }
      id <- idOpt toSuccess {
        Job save(jobF.assignTo(user)) fold (
          e => failWithGrace(e),
          _ => seeDashboard(Created, ("info" -> "Job created")))
      }
      jobC <- getJobConfIfAllowed(user, id) failMap failWithGrace
      _ <- Job save (jobC.copy(strategy = jobF.strategy, name = jobF.name)) failMap { e => failWithGrace(e) }
    } yield seeDashboard(Ok, ("info" -> "Job updated"))).fold(f => f, s => s)
  }

  def login = Action { implicit req =>
    getAuthenticatedUser.fold(
      ex => ex match {
        case s: StoreException => failWithGrace(s)
        case _ => Ok(views.html.login(loginForm)) // Other exceptions are just silent
      },
      user => Redirect(routes.Dashboard.dashboard) // If the user is already logged in send him to the dashboard
    )
  }

  def logout = Action {
    Redirect(routes.Dashboard.login).withNewSession.flashing("success" -> "You've been logged out")
  }

  def authenticate = Action { implicit req =>
    (for {
      userF <- isValidForm(loginForm) failMap { formWithErrors => BadRequest(views.html.login(formWithErrors)) }
      userO <- User authenticate (userF._1, userF._2) failMap { e => failWithGrace(e) }
      user <- userO toSuccess Unauthorized(views.html.login(loginForm)).withNewSession
    } yield Redirect(routes.Dashboard.dashboard).withSession("email" -> user.email)).fold(f => f, s => s)
  }

  def onJob(implicit id: JobId) = simpleJobAction(user => job => job.on())("run on")

  def offJob(implicit id: JobId) = simpleJobAction(user => job => job.off())("run off")

  def refreshJob(implicit id: JobId) = simpleJobAction(user => job => job.refresh())("run refresh")

  def stopJob(implicit id: JobId) = simpleJobAction(user => job => job.stop())("run stop")

  private def simpleJobAction(action: User => Job => Any)(msg: String)(implicit id: JobId) = Action { implicit req =>
    (for {
      user <- getAuthenticatedUser
      job <- getJobIfAllowed(user, id)
    } yield {
      action(user)(job)
      seeDashboard(Accepted, ("info", msg))
    }).fold(
      e => failWithGrace(e),
      s => s
    )
  }

  private def seeDashboard(status: Status, message: (String, String))(implicit req: Request[_]): Result = {
    if (isAjax) status else SeeOther(routes.Dashboard.dashboard.toString).flashing(message)
  }

  private def failWithGrace(e: SuiteException)(implicit req: Request[_]): Result = {
    e match {
      case UnknownJob => {
        if (isAjax) NotFound
        else SeeOther(routes.Dashboard.dashboard.toString).flashing(("error" -> "Unknown Job"))
      }
      case _@ (UnknownUser | Unauthenticated) => {
        if (isAjax) Unauthorized
        else Unauthorized(views.html.login(loginForm)).withNewSession
      }
      case UnauthorizedJob => {
        if (isAjax) Forbidden
        else Forbidden(views.html.error(List(("error" -> "Forbidden"))))
      }
      case StoreException(t) => {
        if (isAjax) InternalServerError
        else InternalServerError(views.html.error(List(("error" -> "Store Exception"))))
      }
      case Timeout(t) => {
        if (isAjax) InternalServerError
        else InternalServerError(views.html.error(List(("error" -> "Timeout Exception"))))
      }
    }
  }

  private def isValidForm[E](form: Form[E])(implicit req: Request[_]) = form.bindFromRequest.toValidation

  private def isAjax(implicit req: Request[_]) = {
    req.headers get ("x-requested-with") match {
      case Some("XMLHttpRequest") => true
      case _ => false
    }
  }

  private def getJobIfAllowed(user: User, id: JobId): Validation[SuiteException, Job] = getJobConfIfAllowed(user, id).map(Jobs.getJobOrCreate(_))

  private def getJobConfIfAllowed(user: User, id: JobId): Validation[SuiteException, JobConfiguration] = {
    for {
      jobConfO <- Job get (id)
      jobConf <- jobConfO toSuccess UnknownJob
      jobConfAllowed <- if (jobConf.organization === user.organization) Success(jobConf) else Failure(UnauthorizedJob)
    } yield jobConfAllowed
  }
  
  private implicit def getAuthenticatedUser(implicit session: Session): Validation[SuiteException, User] = {
    for {
      email <- session get ("email") toSuccess Unauthenticated
      userO <- User getByEmail (email)
      user <- userO toSuccess UnknownUser
    } yield user
  }

  // * Sockets
  def dashboardSocket() = WebSocket.using[JsValue] { implicit req =>
    getAuthenticatedUser.fold(
      f => (Iteratee.ignore[JsValue], Enumerator.eof),
      user => {
        val in = Iteratee.ignore[JsValue]
        val jobConfs = Job.getAll(user.organization) fold (t => throw new Exception(), jobs => jobs)
        // The seed for the future scan, ie the initial jobData of a run
        def seed = new UpdateData(null)
        // Mapping through a list of (jobId, enum)
        var out = jobConfs.map(jobConf => Jobs.getJobOrCreate(jobConf).subscribeToUpdates).map { enum =>
          // Filter the enumerator, taking only the UpdateData messages
          enum &> Enumeratee.collect[RunUpdate] { case e: UpdateData => e } &>
            // Transform to a tuple (updateData, sameAsPrevious)
            Enumeratee.scanLeft[UpdateData]((seed, false)) { (from: (UpdateData, Boolean), to: UpdateData) =>
              from match {
                case (prev, _) if (to != prev) => (to, false)
                case _ => (to, true)
              }
            }
          // Interleave the resulting enumerators
        }.reduce((e1, e2) => e1 >- e2) &>
          // And collect messages that are marked as changed
          Enumeratee.collect { case (a, false) => a.toJS }
        (in, out)
      })
  }
  // jobSocket
  // uriSocket

}
