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
import akka.pattern.ask
import akka.persistence.PersistentRepr
import akka.persistence.inmemory.extension.{ InMemoryJournalStorage, StorageExtension }
import akka.persistence.query.EventEnvelope
import akka.persistence.query.journal.leveldb.DeliveryBuffer
import akka.serialization.SerializationExtension
import akka.stream.Materializer
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{ Cancel, Request }
import akka.stream.scaladsl.{ Sink, Source }
import akka.util.Timeout

import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.concurrent.{ ExecutionContext, Future }

object EventsByPersistenceIdPublisher {
  sealed trait Command
  case object GetMessages extends Command
  case object BecomePolling extends Command
  case object DetermineSchedulePoll extends Command
}

class EventsByPersistenceIdPublisher(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, refreshInterval: FiniteDuration, maxBufferSize: Int)(implicit ec: ExecutionContext, mat: Materializer, timeout: Timeout) extends ActorPublisher[EventEnvelope] with DeliveryBuffer[EventEnvelope] with ActorLogging {
  import EventsByPersistenceIdPublisher._
  val journal = StorageExtension(context.system).journalStorage
  val serialization = SerializationExtension(context.system)

  def determineSchedulePoll(): Unit = {
    if (buf.size < maxBufferSize && totalDemand > 0)
      context.system.scheduler.scheduleOnce(0.seconds, self, BecomePolling)
  }

  val checkPoller = context.system.scheduler.schedule(0.seconds, refreshInterval, self, DetermineSchedulePoll)

  def receive = active(Math.max(1, fromSequenceNr))

  def polling(fromSeqNr: Long): Receive = {
    case GetMessages ⇒
      Source.fromFuture((journal ? InMemoryJournalStorage.GetJournalEntriesExceptDeleted(persistenceId, fromSequenceNr, toSequenceNr, Math.max(0, maxBufferSize - buf.size)))
        .mapTo[List[JournalEntry]])
        .mapConcat(identity)
        .take(maxBufferSize - buf.size)
        .map(_.serialized)
        .mapAsync(1)(arr ⇒ Future.fromTry(serialization.deserialize(arr, classOf[PersistentRepr])))
        .map(repr ⇒ EventEnvelope(repr.sequenceNr, repr.persistenceId, repr.sequenceNr, repr.payload))
        .runWith(Sink.seq)
        .map { xs ⇒
          buf = buf ++ xs
          val newFromSeqNr = xs.lastOption.map(_.sequenceNr + 1).getOrElse(fromSeqNr)
          deliverBuf()
          context.become(active(newFromSeqNr))
        }
        .recover {
          case t: Throwable ⇒
            log.error(t, "Error while polling eventsByPersistenceIds")
            onError(t)
            context.stop(self)
        }

    case Cancel ⇒ context.stop(self)
  }

  def active(fromSeqNr: Long): Receive = {
    case BecomePolling ⇒
      context.become(polling(fromSeqNr))
      self ! GetMessages

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