package hu.thsoft.firebasemodel

import scala.scalajs.js
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
import upickle.default.writeJs
import monifu.reactive.Subscriber
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.scalajs.js.Thenable.Implicits.thenable2future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.ClassTag

// TODO error handling

trait Mapping[T] {
  def observe(firebase: Firebase): Observable[Remote[T]]
  def set(firebase: Firebase, value: T): Future[Unit]
}

case class Remote[+T](
  firebase: Firebase,
  value: T
)

case class Cancellation(cancellation: js.Any) extends Throwable

case class Field[FieldValue, Record](
  key: String,
  mapping: Mapping[FieldValue],
  get: Record => Remote[FieldValue]
)

case class Alternative[T](
  typeName: String,
  getMapping: () => Mapping[T]
)

object Mapping {

  type Stored[+T] = Remote[T]

  def observeRaw(firebase: Firebase): Observable[FirebaseDataSnapshot] =
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

  def always[T](value: T): Mapping[T] =
    new Mapping[T] {
      def observe(firebase: Firebase) = {
        Observable(Remote(firebase, value))
      }
      def set(firebase: Firebase, value: T) = {
        Future(())
      }
    }

  def map[A, B](mapping: Mapping[A])(transformRead: A => B)(transformWrite: B => A): Mapping[B] =
    new Mapping[B] {
      def observe(firebase: Firebase) = {
        mapping.observe(firebase).map(remote =>
          Remote(remote.firebase, transformRead(remote.value))
        )
      }
      def set(firebase: Firebase, value: B) = {
        mapping.set(firebase, transformWrite(value))
      }
    }

  def atomic[T](readJson: Js.Value => T)(writeJson: T => Js.Value): Mapping[T] =
    new Mapping[T] {
      def observe(firebase: Firebase) = {
        observeRaw(firebase).map(snapshot => {
          val snapshotValue = snapshot.`val`
          val json = upickle.json.readJs(snapshotValue)
          Remote(firebase, readJson(json))
        })
      }
      def set(firebase: Firebase, value: T) = {
        firebase.set(upickle.json.writeJs(writeJson(value)).asInstanceOf[js.Any])
      }
    }

  lazy val string: Mapping[String] =
    atomic(readJs[String])(writeJs[String])

  lazy val int: Mapping[Int] =
    atomic(readJs[Int])(writeJs[Int])

  lazy val double: Mapping[Double] =
    atomic(readJs[Double])(writeJs[Double])

  lazy val boolean: Mapping[Boolean] =
    atomic(readJs[Boolean])(writeJs[Boolean])

  def reference[T](mapping: Mapping[T]): Mapping[Remote[T]] =
    new Mapping[Remote[T]] {
      def observe(firebase: Firebase) = {
        val urlObservable = string.observe(firebase)
        urlObservable.switchMap(remoteUrl =>
          mapping.observe(new Firebase(remoteUrl.value)).map(value => Remote(firebase, value))
        )
      }
      def set(firebase: Firebase, reference: Remote[T]) = {
        string.set(firebase, reference.firebase.toString)
      }
    }

  private def field[T, R](field: Field[T, R]): Mapping[T] =
    new Mapping[T] {
      def observe(firebase: Firebase) = {
        field.mapping.observe(firebase.child(field.key))
      }
      def set(firebase: Firebase, value: T) = {
        field.mapping.set(firebase.child(field.key), value)
      }
    }

  private def convertToRecord[Fields, Record](fieldsObservable: Observable[Fields], firebase: Firebase)(makeRecord: Fields => Record) =
    fieldsObservable.map(fields => Remote(firebase, makeRecord(fields)))

  def record1[Field1, Record](makeRecord: Remote[Field1] => Record)(
    field1: Field[Field1, Record]
  ): Mapping[Record] =
    new Mapping[Record] {
      def observe(firebase: Firebase) = {
        val field1Observable = field(field1).observe(firebase)
        convertToRecord(field1Observable, firebase)(makeRecord)
      }
      def set(firebase: Firebase, record: Record) = {
        field(field1).set(firebase, field1.get(record).value)
      }
    }

  def record2[Field1, Field2, Record](makeRecord: (Remote[Field1], Remote[Field2]) => Record)(
    field1: Field[Field1, Record],
    field2: Field[Field2, Record]
  ): Mapping[Record] =
    new Mapping[Record] {
      def observe(firebase: Firebase) = {
        val field1Observable = field(field1).observe(firebase)
        val field2Observable = field(field2).observe(firebase)
        val fieldsObservable = field1Observable.combineLatest(field2Observable)
        convertToRecord(fieldsObservable, firebase)(makeRecord.tupled)
      }
      def set(firebase: Firebase, record: Record) = {
        parallel(
          field(field1).set(firebase, field1.get(record).value),
          field(field2).set(firebase, field2.get(record).value)
        )
      }
    }

  private def parallel(futures: Future[Unit]*): Future[Unit] =
    Future.traverse(futures)(identity).map(_ => ())

  def record3[Field1, Field2, Field3, Record](makeRecord: (Remote[Field1], Remote[Field2], Remote[Field3]) => Record)(
    field1: Field[Field1, Record],
    field2: Field[Field2, Record],
    field3: Field[Field3, Record]
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
      def set(firebase: Firebase, record: Record) = {
        parallel(
          field(field1).set(firebase, field1.get(record).value),
          field(field2).set(firebase, field2.get(record).value),
          field(field3).set(firebase, field3.get(record).value)
        )
      }
    }

  def record4[Field1, Field2, Field3, Field4, Record](makeRecord: (Remote[Field1], Remote[Field2], Remote[Field3], Remote[Field4]) => Record)(
    field1: Field[Field1, Record],
    field2: Field[Field2, Record],
    field3: Field[Field3, Record],
    field4: Field[Field4, Record]
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
      def set(firebase: Firebase, record: Record) = {
        parallel(
          field(field1).set(firebase, field1.get(record).value),
          field(field2).set(firebase, field2.get(record).value),
          field(field3).set(firebase, field3.get(record).value),
          field(field4).set(firebase, field4.get(record).value)
        )
      }
    }

  def choice2[Alternative1 <: Choice : ClassTag, Alternative2 <: Choice : ClassTag, Choice](
    alternative1: Alternative[Alternative1],
    alternative2: Alternative[Alternative2]
  ): Mapping[Choice] =
    new Mapping[Choice] {
      def observe(firebase: Firebase) = {
        val typeNameObservable = string.observe(typeNameChild(firebase))
        typeNameObservable.switchMap(remoteTypeName => {
          remoteTypeName.value match {
            case alternative1.typeName => alternativeObserve(alternative1, firebase)
            case alternative2.typeName => alternativeObserve(alternative2, firebase)
            case _ => Observable.empty
          }
        })
      }

      def set(firebase: Firebase, value: Choice) = {
        value match {
          case value1: Alternative1 => alternativeSet(alternative1, firebase, value1)
          case value2: Alternative2 => alternativeSet(alternative2, firebase, value2)
          case _ => Future()
        }
      }
    }

  private def alternativeObserve[T](alternative: Alternative[T], firebase: Firebase) =
    alternative.getMapping().observe(valueChild(firebase))

  def choice3[Alternative1 <: Choice : ClassTag, Alternative2 <: Choice : ClassTag, Alternative3 <: Choice : ClassTag, Choice](
    alternative1: Alternative[Alternative1],
    alternative2: Alternative[Alternative2],
    alternative3: Alternative[Alternative3]
  ): Mapping[Choice] =
    new Mapping[Choice] {
      def observe(firebase: Firebase) = {
        val typeNameObservable = string.observe(typeNameChild(firebase))
        typeNameObservable.switchMap(remoteTypeName => {
          remoteTypeName.value match {
            case alternative1.typeName => alternativeObserve(alternative1, firebase)
            case alternative2.typeName => alternativeObserve(alternative2, firebase)
            case alternative3.typeName => alternativeObserve(alternative3, firebase)
            case _ => Observable.empty
          }
        })
      }

      def set(firebase: Firebase, value: Choice) = {
        value match {
          case value1: Alternative1 => alternativeSet(alternative1, firebase, value1)
          case value2: Alternative2 => alternativeSet(alternative2, firebase, value2)
          case value3: Alternative3 => alternativeSet(alternative3, firebase, value3)
          case _ => Future()
        }
      }
    }

  def choice4[Alternative1 <: Choice : ClassTag, Alternative2 <: Choice : ClassTag, Alternative3 <: Choice : ClassTag, Alternative4 <: Choice : ClassTag, Choice](
    alternative1: Alternative[Alternative1],
    alternative2: Alternative[Alternative2],
    alternative3: Alternative[Alternative3],
    alternative4: Alternative[Alternative4]
  ): Mapping[Choice] =
    new Mapping[Choice] {
      def observe(firebase: Firebase) = {
        val typeNameObservable = string.observe(typeNameChild(firebase))
        typeNameObservable.switchMap(remoteTypeName => {
          remoteTypeName.value match {
            case alternative1.typeName => alternativeObserve(alternative1, firebase)
            case alternative2.typeName => alternativeObserve(alternative2, firebase)
            case alternative3.typeName => alternativeObserve(alternative3, firebase)
            case alternative4.typeName => alternativeObserve(alternative4, firebase)
            case _ => Observable.empty
          }
        })
      }

      def set(firebase: Firebase, value: Choice) = {
        value match {
          case value1: Alternative1 => alternativeSet(alternative1, firebase, value1)
          case value2: Alternative2 => alternativeSet(alternative2, firebase, value2)
          case value3: Alternative3 => alternativeSet(alternative3, firebase, value3)
          case value4: Alternative4 => alternativeSet(alternative4, firebase, value4)
          case _ => Future()
        }
      }
    }

  private def typeNameChild(firebase: Firebase) = {
    firebase.child("type")
  }

  private def valueChild(firebase: Firebase) = {
    firebase.child("value")
  }

  private def alternativeSet[T](alternative: Alternative[T], firebase: Firebase, value: T) =
    parallel(
      typeNameChild(firebase).set(alternative.typeName),
      alternative.getMapping().set(valueChild(firebase), value)
    )

  type Many[+T] =
    List[Remote[T]]

  def list[T](elementMapping: Mapping[T]): Mapping[Many[T]] =
    new Mapping[Many[T]] {
      def observe(firebase: Firebase) = {
        val listObservable = observeRaw(firebase)
        listObservable.switchMap(snapshot => {
          val children = getChildren(snapshot)
          val updatesByChild = children.map(child =>
            elementMapping.observe(child).map(elementValue => (child.toString, elementValue))
          )
          val elementsByChild = Observable.merge(updatesByChild:_*).scan(Map[String, Remote[T]]())(_ + _)
          elementsByChild.map(elementMap =>
            Remote(firebase, elementMap.toList.sortBy(entry => entry._1).map(entry => entry._2))
          )
        })
      }

      def set(firebase: Firebase, elements: Many[T]) = {
        firebase.remove()
        val futures = elements.map(element => {
          elementMapping.set(element.firebase, element.value)
        })
        parallel(futures:_*)
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