package org.w3.vs.model

import java.nio.channels.ClosedChannelException
import org.joda.time.{ DateTime, DateTimeZone }
import org.w3.util.akkaext._
import org.w3.vs.actor.message._
import akka.actor._
import play.api.libs.iteratee._
import play.Logger
import org.w3.util._
import scalaz.Equal
import scalaz.Equal._
import org.w3.vs._
import diesel._
import ops._
import org.w3.banana._
import org.w3.banana.LinkedDataStore._
import org.w3.vs.store.Binders._
import org.w3.vs.sparql._
import org.w3.vs.actor.JobActor._
import scala.concurrent.{ ops => _, _ }
import scala.concurrent.ExecutionContext.Implicits.global

case class Job(id: JobId, vo: JobVO)(implicit conf: VSConfiguration) {

  import conf._

  val creatorId = vo.creator

  val jobUri: Rdf#URI = JobUri(vo.organization, id)

//  def ldr: LinkedDataResource[Rdf] = LinkedDataResource(uriSyntax[Rdf](jobUri).fragmentLess, vo.toPG)
  def ldr: LinkedDataResource[Rdf] = LinkedDataResource(jobUri.fragmentLess, vo.toPG)

  val orgUri: Rdf#URI = anySyntax(vo.organization).toUri
  val creatorUri: Rdf#URI = vo.creator.toUri

  private val logger = Logger.of(classOf[Job])
  
  def getCreator(): Future[User] =
    User.bananaGet(creatorUri)

  def getOrganization(): Future[Organization] = Organization.get(orgUri)
    
  def getRun(): Future[Run] = {
    (PathAware(organizationsRef, path) ? GetRun).mapTo[Run]
  }

  def getAssertions(): Future[Iterable[Assertion]] = {
    getRun() map {
      run => run.assertions.toIterable
    }
  }

  def getActivity(): Future[RunActivity] = {
    getRun().map(_.activity)
  }

  def getData(): Future[JobData] = {
    getRun().map(_.jobData)
  }


  // Get all runVos for this job, group by id, and for each runId take the latest completed jobData if any
  def getHistory(): Future[Iterable[JobData]] = {
    sys.error("")
  }

  def getCompletedOn(): Future[Option[DateTime]] = {
    Job.getLastCompleted(jobUri)
  }
  
  def save(): Future[Job] = Job.save(this)
  
  def delete(): Future[Unit] = {
    cancel()
    Job.delete(this)
  }
  
  def run(): Future[(OrganizationId, JobId, RunId)] = 
    (PathAware(organizationsRef, path) ? Refresh).mapTo[(OrganizationId, JobId, RunId)]
  
  def cancel(): Unit = 
    PathAware(organizationsRef, path) ! Stop

  def on(): Unit = 
    PathAware(organizationsRef, path) ! BeProactive

  def off(): Unit = 
    PathAware(organizationsRef, path) ! BeLazy

  def resume(): Unit = 
    PathAware(organizationsRef, path) ! Resume

  def getSnapshot(): Future[JobData] =
    (PathAware(organizationsRef, path) ? GetSnapshot).mapTo[JobData]

  lazy val enumerator: Enumerator[RunUpdate] = {
    val (_enumerator, channel) = Concurrent.broadcast[RunUpdate]
    val subscriber: ActorRef = system.actorOf(Props(new Actor {
      def receive = {
        case msg: RunUpdate =>
          try {
            channel.push(msg)
          } catch { 
            case e: ClosedChannelException => {
              logger.error("ClosedChannel exception: ", e)
              channel.eofAndEnd()
            }
            case e => {
              logger.error("Enumerator exception: ", e)
              channel.eofAndEnd()
            }
          }
        case msg => logger.error("subscriber got " + msg)
      }
    }))
    listen(subscriber)
    _enumerator
  }

  def listen(implicit listener: ActorRef): Unit =
    PathAware(organizationsRef, path).tell(Listen(listener), listener)
  
  def deafen(implicit listener: ActorRef): Unit =
    PathAware(organizationsRef, path).tell(Deafen(listener), listener)
  
  private val organizationsRef = system.actorFor(system / "organizations")

  private val path = {
    /* val relPath = jobUri.relativize(URI("https://validator.w3.org/suite/")).getString */
    system / "organizations" / vo.organization.id / "jobs" / id.id
  }
  
  def !(message: Any)(implicit sender: ActorRef = null): Unit =
    PathAware(organizationsRef, path) ! message

}

object Job {

  def apply(
    id: JobId = JobId(),
    name: String,
    createdOn: DateTime = DateTime.now(DateTimeZone.UTC),
    strategy: Strategy,
    creator: UserId,
    organization: OrganizationId)(
    implicit conf: VSConfiguration): Job =
      Job(id, JobVO(name, createdOn, strategy, creator, organization))

  implicit def toVO(job: Job): JobVO = job.vo

  def get(orgId: OrganizationId, jobId: JobId)(implicit conf: VSConfiguration): Future[(Job, Option[Rdf#URI])] =
    get(JobUri(orgId, jobId))

  def bananaGet(orgId: OrganizationId, jobId: JobId)(implicit conf: VSConfiguration): Future[(Job, Option[Rdf#URI])] =
    bananaGet((orgId, jobId).toUri)

  def bananaGet(jobUri: Rdf#URI)(implicit conf: VSConfiguration): Future[(Job, Option[Rdf#URI])] = {
    import conf._
    for {
      ids <- JobUri.fromUri(jobUri).asFuture
      jobLDR <- store.asLDStore.GET(jobUri)
      runUriOpt <- (jobLDR.resource / ont.run).asOption[Rdf#URI].asFuture
      jobVO <- jobLDR.resource.as[JobVO].asFuture
    } yield (Job(ids._2, jobVO), runUriOpt)
  }

  def get(jobUri: Rdf#URI)(implicit conf: VSConfiguration): Future[(Job, Option[Rdf#URI])] = {
    import conf._
    bananaGet(jobUri)
  }

  def getFor(userId: UserId)(implicit conf: VSConfiguration): Future[Iterable[Job]] = {
    import conf._
    val query = """
CONSTRUCT {
  ?user ont:job ?job .
  ?s2 ?p2 ?o2
} WHERE {
  BIND (iri(strbefore(str(?user), "#")) AS ?userG) .
  graph ?userG {
    ?user ont:job ?job .
  } .
  BIND (iri(strbefore(str(?job), "#")) AS ?jobG) .
  graph ?jobG {
    ?s2 ?p2 ?o2
  }
}
"""
    val construct = ConstructQuery(query, ont)
    for {
      graph <- store.executeConstruct(construct, Map("user" -> userId.toUri))
      pointedUser = PointedGraph[Rdf](userId.toUri, graph)
      it <- (pointedUser / ont.job).asSet2[(OrganizationId, JobId), JobVO].asFuture
    } yield {
      it map { case (ids, jobVO) => Job(ids._2, jobVO) }
    }
  }

  def getLastCompleted(jobUri: Rdf#URI)(implicit conf: VSConfiguration): Future[Option[DateTime]] = {
    import conf._
    val query = """
SELECT ?timestamp WHERE {
  BIND (iri(strbefore(str(?job), "#")) AS ?jobG) .
  graph ?jobG {
    ?job ont:lastRun ?run
  } .
  BIND (iri(strbefore(str(?run), "#")) AS ?runG) .
  graph ?runG {
    ?run ont:completedOn ?timestamp
  }
}
"""
    val select = SelectQuery(query, ont)
    store.executeSelect(select, Map("job" -> jobUri)) flatMap { rows =>
      val rds: Iterable[DateTime] = rows.toIterable map { row =>
        val timestamp = row("timestamp").flatMap(_.as[DateTime]).get
        timestamp
      }
      rds.headOption.asFuture
    }
  }

  def save(job: Job)(implicit conf: VSConfiguration): Future[Job] = {
    import conf._
    val orgUri = job.vo.organization.toUri
    val creatorUri = job.vo.creator.toUri
    val script = for {
      _ <- Command.PUT[Rdf](job.ldr)
      _ <- Command.POST[Rdf](orgUri, orgUri -- ont.job ->- job.jobUri)
      _ <- Command.POST[Rdf](creatorUri, creatorUri -- ont.job ->- job.jobUri)
    } yield ()
    store.execute(script).map(_ => job)
  }

  def delete(job: Job)(implicit conf: VSConfiguration): Future[Unit] = {
    import conf._
    val script = for {
      _ <- Command.PATCH[Rdf](job.vo.creator.toUri,
                              tripleMatches = List((job.vo.creator.toUri, ont.job.uri, job.jobUri)))
      _ <- Command.PATCH[Rdf](job.orgUri,
                              tripleMatches = List((job.orgUri, ont.job.uri, job.jobUri)))
      _ <- Command.DELETE[Rdf](job.jobUri.fragmentLess)
    } yield ()
    store.execute(script)
  }

}

