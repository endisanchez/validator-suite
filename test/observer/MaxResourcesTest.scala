package org.w3.vs.run

import org.w3.util._
import org.w3.vs.util._
import org.w3.util.website._
import org.w3.vs.model._
import akka.dispatch.Await
import akka.util.duration._
import akka.util.Duration
import java.util.concurrent.TimeUnit.SECONDS
import org.w3.vs.DefaultProdConfiguration
import org.w3.vs.actor._
import org.w3.vs.actor.message._
import org.w3.util.akkaext._
import org.w3.vs.http._

class MaxResourcesTest extends RunTestHelper(new DefaultProdConfiguration { }) with TestKitHelper {

  val strategy =
    Strategy(
      name="localhost:9001",
      entrypoint=URL("http://localhost:9001/"),
      distance=1000,
      linkCheck=true,
      maxNumberOfResources = 100,
      filter=Filter(include=Everything, exclude=Nothing)).noAssertor()
  
  val job = Job(strategy = strategy, creatorId = userTest.id, organizationId = organizationTest.id, name = "@@")
  
  val servers = Seq(unfiltered.jetty.Http(9001).filter(Website.tree(4).toPlanify))

  "shoudldn't access more that 100 resources" in {
    stores.OrganizationStore.put(organizationTest)
    store.putJob(job).waitResult()
    PathAware(http, http.path / "localhost_9001") ! SetSleepTime(0)
    job.run()
    job.listen(testActor)
    fishForMessagePF(3.seconds) {
      case UpdateData(jobData) if jobData.activity == Idle => {
        val resources = store.listResourceInfos(job.id).waitResult()
        resources must have size (100)
      }
    }
  }
  
}
