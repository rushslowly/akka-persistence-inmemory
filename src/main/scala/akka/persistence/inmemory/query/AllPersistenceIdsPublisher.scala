/*
 * Copyright 2016 Dennis Vriend
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.persistence.inmemory
package query

import akka.actor.ActorLogging
import akka.event.LoggingReceive
import akka.persistence.inmemory.extension.{ InMemoryJournalStorage, StorageExtension }
import akka.persistence.query.journal.leveldb.DeliveryBuffer
import akka.stream.Materializer
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{ Cancel, Request }
import akka.stream.scaladsl.{ Sink, Source }
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{ FiniteDuration, _ }

object AllPersistenceIdsPublisher {
  sealed trait Command
  case object GetAllPersistenceIds extends Command
  case object BecomePolling extends Command
  case object DetermineSchedulePoll extends Command
}

class AllPersistenceIdsPublisher(refreshInterval: FiniteDuration, maxBufferSize: Int)(implicit ec: ExecutionContext, mat: Materializer, timeout: Timeout) extends ActorPublisher[String] with DeliveryBuffer[String] with ActorLogging {
  import AllPersistenceIdsPublisher._
  val journal = StorageExtension(context.system).journalStorage

  def determineSchedulePoll(): Unit = {
    if (buf.size < maxBufferSize && totalDemand > 0)
      context.system.scheduler.scheduleOnce(0.seconds, self, BecomePolling)
  }

  val checkPoller = context.system.scheduler.schedule(0.seconds, refreshInterval, self, DetermineSchedulePoll)

  def receive = active(Set.empty[String])

  def polling(knownIds: Set[String]): Receive = LoggingReceive {
    case GetAllPersistenceIds ⇒
      Source.fromFuture((journal ? InMemoryJournalStorage.AllPersistenceIds)
        .mapTo[Set[String]])
        .mapConcat(identity)
        .take(Math.max(0, maxBufferSize - buf.size))
        .runWith(Sink.seq).map { ids ⇒
          val xs = ids.toSet.diff(knownIds).toVector
          buf = buf ++ xs
          deliverBuf()
          context.become(active(knownIds ++ xs))
        }.recover {
          case t: Throwable ⇒
            log.error(t, "Error while polling allPersistenceIds")
            onError(t)
            context.stop(self)
        }

    case Cancel ⇒ context.stop(self)
  }

  def active(knownIds: Set[String]): Receive = LoggingReceive {
    case BecomePolling ⇒
      context.become(polling(knownIds))
      self ! GetAllPersistenceIds

    case DetermineSchedulePoll if (buf.size - totalDemand) < 0 ⇒ determineSchedulePoll()

    case DetermineSchedulePoll                                 ⇒ deliverBuf()

    case Request(req)                                          ⇒ deliverBuf()

    case Cancel                                                ⇒ context.stop(self)
  }

  override def postStop(): Unit = {
    checkPoller.cancel()
    super.postStop()
  }
}