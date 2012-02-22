/*
 * Copyright (C) 2011, 2012 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.spray.io

import akka.actor.{PoisonPill, Props, Actor}


trait ConnectionActors extends IoPeer {

  override protected def createConnectionHandle(key: Key): Handle = {
    lazy val actor = createConnectionActor(key)
    context.actorOf(Props(actor))
    actor
  }

  protected def createConnectionActor(key: Key): IoConnectionActor = new IoConnectionActor(key)

  protected def pipeline: DoublePipelineStage

  class IoConnectionActor(val key: Key) extends Actor with Handle {
    private[this] val pipelines = pipeline.build(context, baseCommandPipeline, baseEventPipeline)

    protected def baseCommandPipeline: Pipeline[Command] = {
      case x: IoPeer.Send => ioWorker ! IoWorker.Send(this, x.buffers)
      case x: IoPeer.Close => ioWorker ! IoWorker.Close(this, x.reason)
      case x: IoPeer.Dispatch => x.receiver ! x.message
      case x => log.warning("commandPipeline: dropped {}", x)
    }

    protected def baseEventPipeline: Pipeline[Event] = {
      case x: IoPeer.Closed =>
        log.debug("Stopping connection actor, connection was closed due to {}", x.reason)
        self ! PoisonPill
      case x: CommandError => log.warning("Received {}", x)
      case x => log.warning("eventPipeline: dropped {}", x)
    }

    protected def receive = {
      case x: Command => pipelines.commandPipeline(x)
      case x: Event => pipelines.eventPipeline(x)
    }

    def handler = self
  }

}