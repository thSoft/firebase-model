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
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

// TODO error handling
// TODO write

trait Mapping[+T] {
  def observe(firebase: Firebase): Observable[Remote[T]]
}

case class Remote[+T](
  firebase: Firebase,
  value: T
)

case class Cancellation(cancellation: js.Any) extends Throwable

case class Field[T](
  key: String,
  mapping: Mapping[T]
)

object Mapping {

  type Stored[+T] = Remote[T]

  def always[T](value: T): Mapping[T] =
    new Mapping[T] {
      def observe(firebase: Firebase) = {
        Observable(Remote(firebase, value))
      }
    }

  def raw: Mapping[FirebaseDataSnapshot] =
    new Mapping[FirebaseDataSnapshot] {
      def observe(firebase: Firebase) = {
        new ConnectableObservable[Remote[FirebaseDataSnapshot]] {

          private val channel = PublishChannel[Remote[FirebaseDataSnapshot]](OverflowStrategy.Unbounded)(monifu.concurrent.Implicits.globalScheduler)

          private lazy val subscription = {
            val eventType = "value"
            val callback =
              (snapshot: FirebaseDataSnapshot, previousKey: js.UndefOr[String]) => {
                channel.pushNext(Remote(firebase, snapshot))
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

          override def onSubscribe(subscriber: Subscriber[Remote[FirebaseDataSnapshot]]): Unit = {
            channel.onSubscribe(subscriber)
          }

        }.refCount
      }
    }

  def map[A, B](mapping: Mapping[A])(transform: A => B): Mapping[B] =
    new Mapping[B] {
      def observe(firebase: Firebase) = {
        mapping.observe(firebase).map(remote =>
          Remote(remote.firebase, transform(remote.value))
        )
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
        urlObservable.switchMap(remoteUrl =>
          mapping.observe(new Firebase(remoteUrl.value))
        )
      }
    }

  private def field[T](field: Field[T]): Mapping[T] =
    new Mapping[T] {
      def observe(firebase: Firebase) = {
        field.mapping.observe(firebase.child(field.key))
      }
    }

  private def convertToRecord[Fields, Record](fieldsObservable: Observable[Fields], firebase: Firebase)(makeRecord: Fields => Record) =
    fieldsObservable.map(fields => Remote(firebase, makeRecord(fields)))

  def record1[Field1, Record](makeRecord: Remote[Field1] => Record)(
    field1: Field[Field1]
  ): Mapping[Record] =
    new Mapping[Record] {
      def observe(firebase: Firebase) = {
        val field1Observable = field(field1).observe(firebase)
        convertToRecord(field1Observable, firebase)(makeRecord)
      }
    }

  def record2[Field1, Field2, Record](makeRecord: (Remote[Field1], Remote[Field2]) => Record)(
    field1: Field[Field1],
    field2: Field[Field2]
  ): Mapping[Record] =
    new Mapping[Record] {
      def observe(firebase: Firebase) = {
        val field1Observable = field(field1).observe(firebase)
        val field2Observable = field(field2).observe(firebase)
        val fieldsObservable = field1Observable.combineLatest(field2Observable)
        convertToRecord(fieldsObservable, firebase)(makeRecord.tupled)
      }
    }

  def record3[Field1, Field2, Field3, Record](makeRecord: (Remote[Field1], Remote[Field2], Remote[Field3]) => Record)(
    field1: Field[Field1],
    field2: Field[Field2],
    field3: Field[Field3]
  ): Mapping[Record] =
    new Mapping[Record] {
      def observe(firebase: Firebase) = {
        val field1Observable = field(field1).observe(firebase)
        val field2Observable = field(field2).observe(firebase)
        val field3Observable = field(field3).observe(firebase)
        val fieldsObservable =
          field1Observable
            .combineLatest(field2Observable)
            .combineLatest(field3Observable)
            .map { case ((f1, f2), f3) => (f1, f2, f3) }
        convertToRecord(fieldsObservable, firebase)(makeRecord.tupled)
      }
    }

  def record4[Field1, Field2, Field3, Field4, Record](makeRecord: (Remote[Field1], Remote[Field2], Remote[Field3], Remote[Field4]) => Record)(
    field1: Field[Field1],
    field2: Field[Field2],
    field3: Field[Field3],
    field4: Field[Field4]
  ): Mapping[Record] =
    new Mapping[Record] {
      def observe(firebase: Firebase) = {
        val field1Observable = field(field1).observe(firebase)
        val field2Observable = field(field2).observe(firebase)
        val field3Observable = field(field3).observe(firebase)
        val field4Observable = field(field4).observe(firebase)
        val fieldsObservable =
          field1Observable
            .combineLatest(field2Observable)
            .combineLatest(field3Observable)
            .combineLatest(field4Observable)
            .map { case (((f1, f2), f3), f4) => (f1, f2, f3, f4) }
        convertToRecord(fieldsObservable, firebase)(makeRecord.tupled)
      }
    }

  def choice[T](options: (String, () => Mapping[T])*): Mapping[T] =
    new Mapping[T] {
      def observe(firebase: Firebase) = {
        val typeNameObservable = string.observe(firebase.child("type"))
        typeNameObservable.switchMap(remoteTypeName =>
          options.toMap.get(remoteTypeName.value)
            .map(mapping => mapping().observe(firebase.child("value")))
            .getOrElse(Observable.empty)
        )
      }
    }

  type Many[+T] =
    List[Remote[T]]

  def list[T](elementMapping: Mapping[T]): Mapping[Many[T]] =
    new Mapping[Many[T]] {
      def observe(firebase: Firebase) = {
        val listObservable = raw.observe(firebase)
        listObservable.switchMap(remoteSnapshot => {
          val children = getChildren(remoteSnapshot.value)
          val updatesByChild = children.map(child =>
            elementMapping.observe(child).map(elementValue => (child.toString, elementValue))
          )
          val elementsByChild = Observable.merge(updatesByChild:_*).scan(Map[String, Remote[T]]())(_ + _)
          elementsByChild.map(elementMap =>
            Remote(firebase, elementMap.toList.sortBy(entry => entry._1).map(entry => entry._2))
          )
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