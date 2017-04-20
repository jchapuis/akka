/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.testkit

import java.util.concurrent.ConcurrentLinkedQueue

import akka.typed.{ ActorContext, ActorRef, ActorSystem, Behavior, DeploymentConfig, EmptyDeploymentConfig, Signal }

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.duration.{ Duration, FiniteDuration }

/**
 * All tracked effects must extend implement this type. It is deliberately
 * not sealed in order to allow extensions.
 */
abstract class Effect

object Effect {
  @SerialVersionUID(1L) final case class Spawned(childName: String) extends Effect
  @SerialVersionUID(1L) final case class Stopped(childName: String) extends Effect
  @SerialVersionUID(1L) final case class Watched[T](other: ActorRef[T]) extends Effect
  @SerialVersionUID(1L) final case class Unwatched[T](other: ActorRef[T]) extends Effect
  @SerialVersionUID(1L) final case class ReceiveTimeoutSet[T](d: Duration, msg: T) extends Effect
  @SerialVersionUID(1L) final case class Messaged[U](other: ActorRef[U], msg: U) extends Effect
  @SerialVersionUID(1L) final case class Scheduled[U](delay: FiniteDuration, target: ActorRef[U], msg: U) extends Effect
  @SerialVersionUID(1L) case object EmptyEffect extends Effect
}

/**
 * An [[ActorContext]] for testing purposes that records the effects performed
 * on it and otherwise stubs them out like a [[StubbedActorContext]].
 */
class EffectfulActorContext[T](_name: String, _initialBehavior: Behavior[T], _mailboxCapacity: Int, _system: ActorSystem[Nothing])
  extends StubbedActorContext[T](_name, _mailboxCapacity, _system) {
  import Effect._
  import akka.{ actor ⇒ a }

  private val effectQueue = new ConcurrentLinkedQueue[Effect]
  def getEffect(): Effect = effectQueue.poll() match {
    case null ⇒ throw new NoSuchElementException(s"polling on an empty effect queue: $name")
    case x    ⇒ x
  }
  def getAllEffects(): immutable.Seq[Effect] = {
    @tailrec def rec(acc: List[Effect]): List[Effect] = effectQueue.poll() match {
      case null ⇒ acc.reverse
      case x    ⇒ rec(x :: acc)
    }
    rec(Nil)
  }
  def hasEffects: Boolean = effectQueue.peek() != null

  private var current = Behavior.preStart(_initialBehavior, this)

  def currentBehavior: Behavior[T] = current
  def isAlive: Boolean = Behavior.isAlive(current)

  def run(msg: T): Unit = {
    current = Behavior.canonicalize(Behavior.interpretMessage(current, this, msg), current, this)
  }

  def signal(signal: Signal): Unit = {
    current = Behavior.canonicalize(Behavior.interpretSignal(current, this, signal), current, this)
  }

  override def spawnAnonymous[U](behavior: Behavior[U], deployment: DeploymentConfig = EmptyDeploymentConfig): ActorRef[U] = {
    val ref = super.spawnAnonymous(behavior)
    effectQueue.offer(Spawned(ref.path.name))
    ref
  }
  override def spawnAdapter[U](f: U ⇒ T, name: String = ""): ActorRef[U] = {
    val ref = super.spawnAdapter(f, name)
    effectQueue.offer(Spawned(ref.path.name))
    ref
  }
  override def spawn[U](behavior: Behavior[U], name: String, deployment: DeploymentConfig = EmptyDeploymentConfig): ActorRef[U] = {
    effectQueue.offer(Spawned(name))
    super.spawn(behavior, name)
  }
  override def stop(child: ActorRef[_]): Boolean = {
    effectQueue.offer(Stopped(child.path.name))
    super.stop(child)
  }
  override def watch(other: ActorRef[_]): Unit = {
    effectQueue.offer(Watched(other))
    super.watch(other)
  }
  override def unwatch(other: ActorRef[_]): Unit = {
    effectQueue.offer(Unwatched(other))
    super.unwatch(other)
  }
  override def setReceiveTimeout(d: FiniteDuration, msg: T): Unit = {
    effectQueue.offer(ReceiveTimeoutSet(d, msg))
    super.setReceiveTimeout(d, msg)
  }
  override def cancelReceiveTimeout(): Unit = {
    effectQueue.offer(ReceiveTimeoutSet(Duration.Undefined, null))
    super.cancelReceiveTimeout()
  }
  override def schedule[U](delay: FiniteDuration, target: ActorRef[U], msg: U): a.Cancellable = {
    effectQueue.offer(Scheduled(delay, target, msg))
    super.schedule(delay, target, msg)
  }
}