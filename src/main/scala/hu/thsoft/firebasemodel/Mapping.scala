package hu.thsoft.firebasemodel

import scala.scalajs.js
import scala.scalajs.js.Any.fromFunction1
import scala.scalajs.js.Any.fromFunction2
import hu.thsoft.firebase.Firebase
import hu.thsoft.firebase.FirebaseDataSnapshot
import monifu.concurrent.Implicits
import monifu.concurrent.cancelables.BooleanCancelable
import monifu.reactive.Observable
import monifu.reactive.OverflowStrategy
import monifu.reactive.channels.PublishChannel
import monifu.reactive.observables.ConnectableObservable
import upickle.Js
import upickle.default.StringRW
import upickle.default.readJs
import monifu.reactive.Subscriber
import scalaz.Applicative
import scalaz.syntax.ApplyOps
import scala.collection.mutable.ListBuffer

// TODO trace
// TODO error handling
// TODO write

trait Mapping[+T] {
  def observe(firebase: Firebase): Observable[T]
}

case class Cancellation(cancellation: js.Any) extends Throwable

object Mapping {

  def always[T](value: T): Mapping[T] =
    new Mapping[T] {
      def observe(firebase: Firebase) = {
        Observable(value)
      }
    }

  def raw: Mapping[FirebaseDataSnapshot] =
    new Mapping[FirebaseDataSnapshot] {
      def observe(firebase: Firebase) = {
        new ConnectableObservable[FirebaseDataSnapshot] {

          private val channel = PublishChannel[FirebaseDataSnapshot](OverflowStrategy.Unbounded)(monifu.concurrent.Implicits.globalScheduler)

          private lazy val subscription = {
            val eventType = "value"
            val callback =
              (snapshot: FirebaseDataSnapshot, previousKey: js.UndefOr[String]) => {
                channel.pushNext(snapshot)
                ()
              }
            val cancelCallback =
              (cancellation: js.Any) => {
                channel.pushError(Cancellation(cancellation))
              }
            firebase.on(eventType, callback, cancelCallback)
            BooleanCancelable {
              channel.pushComplete()
              firebase.off(eventType, callback)
            }
          }

          override def connect() = subscription

          override def onSubscribe(subscriber: Subscriber[FirebaseDataSnapshot]): Unit = {
            channel.onSubscribe(subscriber)
          }

        }.refCount
      }
    }

  def map[A, B](mapping: Mapping[A])(transform: A => B): Mapping[B] =
    new Mapping[B] {
      def observe(firebase: Firebase) = {
        mapping.observe(firebase).map(transform)
      }
    }

  def reader[T](readJson: Js.Value => T): Mapping[T] =
    map(raw)(snapshot => {
      val snapshotValue = snapshot.`val`
      val json = upickle.json.readJs(snapshotValue)
      readJson(json)
    })

  lazy val string: Mapping[String] =
    reader(readJs[String])
  
  lazy val int: Mapping[Int] =
    reader(readJs[Int])

  lazy val double: Mapping[Double] =
    reader(readJs[Double])

  lazy val boolean: Mapping[Boolean] =
    reader(readJs[Boolean])

  def reference[T](mapping: Mapping[T]): Mapping[T] =
    new Mapping[T] {
      def observe(firebase: Firebase) = {
        val urlObservable = string.observe(firebase)
        urlObservable.switchMap(url => {
          mapping.observe(new Firebase(url))
        })
      }
    }

  implicit val applicative = new Applicative[Mapping] {
    def point[A](a: => A) =
      always(a)
    def ap[A, B](as: => Mapping[A])(fs: => Mapping[(A) => B]) =
      new Mapping[B] {
        def observe(firebase: Firebase) = {
          as.observe(firebase).combineLatest(fs.observe(firebase)).map { case (a, f) => f(a) }
        }
      }
  }

  /** Use scalaz's ApplicativeBuilder to create a record mapping from field mappings. */
  def field[T](key: String, mapping: Mapping[T]): Mapping[T] =
    new Mapping[T] {
      def observe(firebase: Firebase) = {
        mapping.observe(firebase.child(key))
      }
    }

  def choice[T](options: (String, () => Mapping[T])*): Mapping[T] =
    new Mapping[T] {
      def observe(firebase: Firebase) = {
        val typeNameObservable = string.observe(firebase.child("type"))
        typeNameObservable.switchMap(typeName => {
          options.toMap.get(typeName)
            .map(_().observe(firebase.child("value")))
            .getOrElse(Observable.empty)
        })
      }
    }

  def list[T](elementMapping: Mapping[T]): Mapping[List[T]] =
    new Mapping[List[T]] {
      def observe(firebase: Firebase) = {
        val listObservable = raw.observe(firebase)
        listObservable.switchMap(snapshot => {
          val children = getChildren(snapshot)
          val updatesByChild = children.map((child: Firebase) => {
            elementMapping.observe(child).map((child.toString, _))
          })
          val elementsByChild = Observable.merge(updatesByChild:_*).scan(Map[String, T]())(_ + _)
          elementsByChild.map(_.values.toList)
        })
      }
    }
  
  private def getChildren(snapshot: FirebaseDataSnapshot): List[Firebase] = {
    val children = ListBuffer[Firebase]()
    snapshot.forEach((child: FirebaseDataSnapshot) => {
      children += child.ref()
      false
    })
    children.toList
  }

}