package org.w3.vs.model

import org.w3.banana._
import org.w3.banana.diesel._
import scalaz._
import scalaz.Scalaz._
import scalaz.Validation._
import org.joda.time.DateTime
import org.w3.util.URL

/**
 * creates [EntityGraphBinder]s for the VS entities
 */
case class Binders[Rdf <: RDF](
  ops: RDFOperations[Rdf],
  union: GraphUnion[Rdf],
  graphTraversal: RDFGraphTraversal[Rdf])
extends UriBuilders[Rdf] with Ontologies[Rdf] with LiteralBinders[Rdf] {

  val diesel: Diesel[Rdf] = Diesel(ops, union, graphTraversal)
  
  import ops._
  import diesel._

  /* helper: to be moved */

  class IfDefined[S](s: S) {
    def ifDefined[T](opt: Option[T])(func: (S, T) => S) = opt match {
      case None => s
      case Some(t) => func(s, t)
    }
  }

  implicit def addIfDefinedMethod[S](s: S): IfDefined[S] = new IfDefined[S](s)

  /* binders for entities */

  val AssertionVOBinder = new PointedGraphBinder[Rdf, AssertionVO] {

    def toPointedGraph(t: AssertionVO): PointedGraph[Rdf] = {
      val pointed = (
        AssertionUri(t.id).a(assertion.Assertion)
          -- assertion.url ->- t.url
          -- assertion.lang ->- t.lang
          -- assertion.title ->- t.title
          -- assertion.severity ->- t.severity
          -- assertion.assertorResponseId ->- AssertorResponseUri(t.assertorResponseId)
      )
      pointed.ifDefined(t.description){ (p, desc) => p -- assertion.description ->- desc }

    }

    def fromPointedGraph(pointed: PointedGraph[Rdf]): Validation[BananaException, AssertionVO] = {
      for {
        id <- pointed.node.asURI flatMap AssertionUri.getId
        url <- (pointed / assertion.url).exactlyOne.flatMap(_.as[URL])
        lang <- (pointed / assertion.lang).exactlyOne.flatMap(_.as[String])
        title <- (pointed / assertion.title).exactlyOne.flatMap(_.as[String])
        severity <- (pointed / assertion.severity).exactlyOne.flatMap(_.as[AssertionSeverity])
        description <- (pointed / assertion.description).headOption match {
          case None => Success(None)
          case Some(pg) => pg.node.as[String] map (Some(_))
        }
        assertorResponseId <- (pointed / assertion.assertorResponseId).exactlyOne.flatMap(_.asURI).flatMap(AssertorResponseUri.getId)
      } yield {
        AssertionVO(id, url, lang, title, severity, description, assertorResponseId)
      }
    }

  }


  val ContextVOBinder = new PointedGraphBinder[Rdf, ContextVO] {

    def toPointedGraph(t: ContextVO): PointedGraph[Rdf] = {
      val pointed = (
        ContextUri(t.id).a(context.Context)
          -- context.content ->- t.content
          -- context.assertionId ->- AssertionUri(t.assertionId)
      )
      pointed
        .ifDefined(t.line){ (p, l) => p -- context.line ->- l }
        .ifDefined(t.column){ (p, c) => p -- context.column ->- c }

    }

    def fromPointedGraph(pointed: PointedGraph[Rdf]): Validation[BananaException, ContextVO] = {
      for {
        id <- pointed.node.asURI flatMap ContextUri.getId
        content <- (pointed / context.content).exactlyOne.flatMap(_.as[String])
        line <- (pointed / context.line).headOption match {
          case None => Success(None)
          case Some(pg) => pg.node.as[Int] map (Some(_))
        }
        column <- (pointed / context.column).headOption match {
          case None => Success(None)
          case Some(pg) => pg.node.as[Int] map (Some(_))
        }
        assertionId <- (pointed / context.assertionId).exactlyOne.flatMap(_.asURI).flatMap(AssertionUri.getId)
      } yield {
        ContextVO(id, content, line, column, assertionId)
      }
    }

  }



  val AssertorResultVOBinder = new PointedGraphBinder[Rdf, AssertorResultVO] {

    def toPointedGraph(t: AssertorResultVO): PointedGraph[Rdf] = (
      AssertorResponseUri(t.id).a(assertorResult.AssertorResult)
        -- assertorResult.jobId ->- JobUri(t.jobId)
        -- assertorResult.runId ->- RunUri(t.runId)
        -- assertorResult.assertorId ->- AssertorUri(t.assertorId)
        -- assertorResult.sourceUrl ->- t.sourceUrl
        -- assertorResult.timestamp ->- t.timestamp
    )

    def fromPointedGraph(pointed: PointedGraph[Rdf]): Validation[BananaException, AssertorResultVO] = {
      for {
        id <- pointed.node.asURI flatMap AssertorResponseUri.getId
        jobId <- (pointed / assertorResult.jobId).exactlyOne.flatMap(_.asURI).flatMap(JobUri.getId)
        runId <- (pointed / assertorResult.runId).exactlyOne.flatMap(_.asURI).flatMap(RunUri.getId)
        assertorId <- (pointed / assertorResult.assertorId).exactlyOne.flatMap(_.asURI).flatMap(AssertorUri.getId)
        sourceUrl <- (pointed / assertorResult.sourceUrl).exactlyOne.flatMap(_.as[URL])
        timestamp <- (pointed / assertorResult.timestamp).exactlyOne.flatMap(_.as[DateTime])
      } yield {
        AssertorResultVO(id, jobId, runId, assertorId, sourceUrl, timestamp)
      }
    }

  }



  val JobVOBinder = new PointedGraphBinder[Rdf, JobVO] {

    def toPointedGraph(t: JobVO): PointedGraph[Rdf] = (
      JobUri(t.id).a(job.Job)
        -- job.name ->- t.name
        -- job.createdOn ->- t.createdOn
        -- job.creator ->- UserUri(t.creatorId)
        -- job.organization ->- OrganizationUri(t.organizationId)
        -- job.strategy ->- StrategyUri(t.strategyId)
    )

    def fromPointedGraph(pointed: PointedGraph[Rdf]): Validation[BananaException, JobVO] = {
      for {
        id <- pointed.node.asURI flatMap JobUri.getId
        name <- (pointed / job.name).exactlyOne.flatMap(_.as[String])
        createdOn <- (pointed / job.createdOn).exactlyOne.flatMap(_.as[DateTime])
        creator <- (pointed / job.creator).exactlyOne.flatMap(_.asURI) flatMap UserUri.getId
        organization <- (pointed / job.organization).exactlyOne.flatMap(_.asURI) flatMap OrganizationUri.getId
        strategy <- (pointed / job.strategy).exactlyOne.flatMap(_.asURI) flatMap StrategyUri.getId
      } yield {
        JobVO(id, name, createdOn, creator, organization, strategy)
      }
    }

  }




  val JobDataVOBinder = new PointedGraphBinder[Rdf, JobDataVO] {

    def toPointedGraph(t: JobDataVO): PointedGraph[Rdf] = (
      JobDataUri(t.id).a(jobData.JobData)
        -- jobData.jobId ->- JobUri(t.jobId)
        -- jobData.runId ->- RunUri(t.runId)
        -- jobData.resources ->- t.resources
        -- jobData.errors ->- t.errors
        -- jobData.warnings ->- t.warnings
        -- jobData.timestamp ->- t.timestamp
    )

    def fromPointedGraph(pointed: PointedGraph[Rdf]): Validation[BananaException, JobDataVO] = {
      for {
        id <- pointed.node.asURI flatMap JobDataUri.getId
        jobId <- (pointed / jobData.jobId).exactlyOne.flatMap(_.asURI).flatMap(JobUri.getId)
        runId <- (pointed / jobData.runId).exactlyOne.flatMap(_.asURI).flatMap(RunUri.getId)
        resources <- (pointed / jobData.resources).exactlyOne.flatMap(_.as[Int])
        errors <- (pointed / jobData.errors).exactlyOne.flatMap(_.as[Int])
        warnings <- (pointed / jobData.warnings).exactlyOne.flatMap(_.as[Int])
        timestamp <- (pointed / jobData.timestamp).exactlyOne.flatMap(_.as[DateTime])
      } yield {
        JobDataVO(id, jobId, runId, resources, errors, warnings, timestamp)
      }
    }

  }




  val OrganizationVOBinder = new PointedGraphBinder[Rdf, OrganizationVO] {

    def toPointedGraph(t: OrganizationVO): PointedGraph[Rdf] = (
      OrganizationUri(t.id).a(organization.Organization)
        -- organization.name ->- t.name
        -- organization.admin ->- UserUri(t.admin)
    )

    def fromPointedGraph(pointed: PointedGraph[Rdf]): Validation[BananaException, OrganizationVO] = {
      for {
        id <- pointed.node.asURI flatMap OrganizationUri.getId
        name <- (pointed / organization.name).exactlyOne.flatMap(_.as[String])
        adminId <- (pointed / organization.admin).exactlyOne.flatMap(_.asURI).flatMap(UserUri.getId)
      } yield {
        OrganizationVO(id, name, adminId)
      }
    }

  }




}






// case class Stores[Rdf <: RDF](
//   store: RDFStore[Rdf],
//   binders: Binders[Rdf]) {

//   import binders._

//   val OrganizationStore = EntityStore(store, OrganizationDataBinder)

// }