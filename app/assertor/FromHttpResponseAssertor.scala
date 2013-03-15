package org.w3.vs.assertor

import org.w3.util._
import org.w3.vs.model._

trait FromHttpResponseAssertor extends FromURLAssertor {
 
  import Assertor.logger

  def supportedMimeTypes: List[String]

  /** this function is the occasion to fix some problems coming from the assertors */
  def assert(runId: RunId, response: HttpResponse, configuration: AssertorConfiguration): AssertorResponse = {
    val start = System.currentTimeMillis()
    val result = try {
      // we group the assertions per url, then by title, then we merge
      // the assertions sharing the same contexts into one single
      // assertions. We do that here because this property is not
      // enforced at the Unicorn level.
      val assertions =
        assert(response.url, configuration).groupBy(_.url).mapValues[Vector[Assertion]] { assertionsCommonURL =>
          var assertionsWithGroupedTitles = Vector.empty[Assertion]
          assertionsCommonURL.groupBy(_.title).values.foreach { assertionsCommonTitle =>
            var groupedContexts = Vector.empty[Context]
            assertionsCommonTitle foreach { groupedContexts ++= _.contexts }
            val newAssertion = assertionsCommonTitle.head.copy(contexts = groupedContexts)
            assertionsWithGroupedTitles :+= newAssertion
          }
          assertionsWithGroupedTitles
        }.values.flatten
      AssertorResult(runId = runId, assertor = id, sourceUrl = response.url, assertions = assertions.toVector)
    } catch { case t: Throwable =>
      AssertorFailure(runId = runId, assertor = id, sourceUrl = response.url, why = t.getMessage)
    }
    val end = System.currentTimeMillis()
    logger.debug("%s took %dms to assert %s" format (this.name, end - start, response.url))
    result
  }

}
