package org.w3.vs.run

import org.w3.util._
import org.w3.vs.util._
import org.w3.util.website._
import org.w3.vs.model._
import org.w3.util.akkaext._
import org.w3.vs.http._
import org.w3.vs.http.Http._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import org.w3.util.Util._
import play.api.libs.iteratee._

/**
  * Server 1 -> Server 2
  * 1 GET       10 HEAD
  */
class FilteredTreeWebsiteTest extends RunTestHelper with TestKitHelper {
  
  val strategy =
    Strategy(
      entrypoint=URL("http://localhost:9001/1/"),
      linkCheck=true,
      maxResources = 50,
      filter=Filter.includePrefixes("http://localhost:9001/1", "http://localhost:9001/3"),
      assertorsConfiguration = Map.empty)
  
  val job = Job.createNewJob(name = "@@", strategy = strategy, creatorId = userTest.id)

  val servers = Seq(Webserver(9001, Website.tree(4).toServlet))

  "test FilteredTreeWebsiteTest" in {

    (for {
      _ <- User.save(userTest)
      _ <- Job.save(job)
    } yield ()).getOrFail()

    PathAware(http, http.path / "localhost_9001") ! SetSleepTime(0)

    val runningJob = job.run().getOrFail()
    val Running(runId, actorPath) = runningJob.status

    val events = (runningJob.runEvents() |>>> Iteratee.getChunks[RunEvent]).getOrFail(3.seconds)

    val completeRunEvent = events.collectFirst { case event: CompleteRunEvent => event }.get
    completeRunEvent.runData.resources must be(50)

    val rrs = events.collect { case ResourceResponseEvent(_, _, _, rr, _) => rr }
    rrs foreach { rr =>
      rr.url.toString must startWith regex ("http://localhost:9001/[13]")
    }

  }

}
