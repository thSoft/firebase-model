package hu.thsoft.firebasemodel

import scala.scalajs.js
import hu.thsoft.firebase.Firebase
import hu.thsoft.firebase.FirebaseDataSnapshot
import upickle.Js
import upickle.default.StringRW
import upickle.default.readJs
import upickle.default.writeJs
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.scalajs.js.Thenable.Implicits.thenable2future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.ClassTag
import Mapping.Stored
import monix.reactive.Observable
import monix.reactive.observables.ConnectableObservable
import monix.execution.cancelables.BooleanCancelable
import monix.reactive.observers.Subscriber
import monix.reactive.OverflowStrategy
import monix.execution.Scheduler
import monix.reactive.subjects.PublishSubject

trait Mapping[T] {
  def observe(firebase: Firebase): Observable[Stored[T]]
  def set(firebase: Firebase, value: T): Future[Unit]
  def push(parent: Firebase, value: T): Future[Stored[T]] = {
    val child = parent.push(null)
    set(child, value).map(_ => Remote(child, Right(value)))
  }
}

case class Remote[+T](
  firebase: Firebase,
  value: T
)

case class Invalid(json: Js.Value, expectedTypeName: String, error: Throwable)

case class Cancellation(cancellation: js.Any) extends Throwable

case class Field[FieldValue, Record](
  key: String,
  mapping: Mapping[FieldValue],
  get: Record => Stored[FieldValue]
)

case class Alternative[T](
  typeName: String,
  getMapping: () => Mapping[T]
)

object Mapping {

  type Stored[+T] = Remote[Either[Invalid, T]]

  def observeRaw(firebase: Firebase): Observable[FirebaseDataSnapshot] =
    new ConnectableObservable[FirebaseDataSnapshot] {

      private val channel = PublishSubject[FirebaseDataSnapshot]

      private lazy val subscription = {
        val eventType = "value"
        val callback =
          (snapshot: FirebaseDataSnapshot, previousKey: js.UndefOr[String]) => {
            channel.onNext(snapshot)
            ()
          }
        val cancelCallback =
          (cancellation: js.Any) => {
            channel.onError(Cancellation(cancellation))
          }
        try {
          firebase.on(eventType, callback, cancelCallback)
        } catch {
          case e: Throwable => channel.onError(e)
        }
        BooleanCancelable(() => {
          channel.onComplete()
          firebase.off(eventType, callback)
        })
      }

      override def connect() = subscription

      override def unsafeSubscribeFn(subscriber: Subscriber[FirebaseDataSnapshot]) = {
        channel.unsafeSubscribeFn(subscriber)
      }

    }.refCount

  def always[T](value: T): Mapping[T] =
    new Mapping[T] {
      def observe(firebase: Firebase) = {
        Observable.pure(Remote(firebase, Right(value)))
      }
      def set(firebase: Firebase, value: T) = {
        Future(())
      }
    }

  def map[A, B](mapping: Mapping[A])(transformRead: A => B)(transformWrite: B => A): Mapping[B] =
    new Mapping[B] {
      def observe(firebase: Firebase) = {
        mapping.observe(firebase).map(remote =>
          Remote(remote.firebase, remote.value.right.map(transformRead))
        )
      }
      def set(firebase: Firebase, value: B) = {
        mapping.set(firebase, transformWrite(value))
      }
    }

  def atomic[T](readJson: Js.Value => T)(writeJson: T => Js.Value)(typeName: String): Mapping[T] =
    new Mapping[T] {
      def observe(firebase: Firebase) = {
        observeRaw(firebase).map(snapshot => {
          val snapshotValue = snapshot.`val`
          val json = upickle.json.readJs(snapshotValue)
          val value =
            json match {
              case Js.Null => Left(Invalid(null, typeName, new NullPointerException))
              case _ =>
                try {
                  Right(readJson(json))
                } catch {
                  case e: Throwable => Left(Invalid(json, typeName, e))
                }
            }
          Remote(firebase, value)
        })
      }
      def set(firebase: Firebase, value: T) = {
        firebase.set(upickle.json.writeJs(writeJson(value)).asInstanceOf[js.Any])
      }
    }

  lazy val string: Mapping[String] =
    atomic(readJs[String])(writeJs[String])("string")

  lazy val int: Mapping[Int] =
    atomic(readJs[Int])(writeJs[Int])("integer")

  lazy val double: Mapping[Double] =
    atomic(readJs[Double])(writeJs[Double])("double")

  lazy val boolean: Mapping[Boolean] =
    atomic(readJs[Boolean])(writeJs[Boolean])("boolean")

  def reference[T](mapping: Mapping[T]): Mapping[Stored[T]] =
    new Mapping[Stored[T]] {
      def observe(firebase: Firebase) = {
        val urlObservable = string.observe(firebase)
        urlObservable.switchMap(remoteUrl =>
          remoteUrl.value match {
            case Left(error) => Observable.pure(Remote(firebase, Left(error)))
            case Right(url) =>
              mapping.observe(new Firebase(url))
                .map(value => Remote(firebase, Right(value)))
          }
        )
      }
      def set(firebase: Firebase, reference: Stored[T]) = {
        string.set(firebase, reference.firebase.toString)
      }
    }

  private def fieldMapping[T, R](field: Field[T, R]): Mapping[T] =
    new Mapping[T] {
      def observe(firebase: Firebase) = {
        field.mapping.observe(firebase.child(field.key))
      }
      def set(firebase: Firebase, value: T) = {
        field.mapping.set(firebase.child(field.key), value)
      }
    }

  private def convertToRecord[Fields, Record](fieldsObservable: Observable[Fields], firebase: Firebase)(makeRecord: Fields => Record) =
    fieldsObservable.map(fields => Remote(firebase, Right(makeRecord(fields))))

  private def setField[FieldValue, Record](field: Field[FieldValue, Record], record: Record, firebase: Firebase) =
    field.get(record).value.right.toOption.map(value => fieldMapping(field).set(firebase, value)).getOrElse(Future())

  def record[Field1, Record](makeRecord: Stored[Field1] => Record)(
    field1: Field[Field1, Record]
  ): Mapping[Record] =
    new Mapping[Record] {
      def observe(firebase: Firebase) = {
        val field1Observable = fieldMapping(field1).observe(firebase)
        convertToRecord(field1Observable, firebase)(makeRecord)
      }
      def set(firebase: Firebase, record: Record) = {
        setField(field1, record, firebase)
      }
    }

  def record[Field1, Field2, Record](makeRecord: (Stored[Field1], Stored[Field2]) => Record)(
    field1: Field[Field1, Record],
    field2: Field[Field2, Record]
  ): Mapping[Record] =
    new Mapping[Record] {
      def observe(firebase: Firebase) = {
        val field1Observable = fieldMapping(field1).observe(firebase)
        val field2Observable = fieldMapping(field2).observe(firebase)
        val fieldsObservable = field1Observable.combineLatest(field2Observable)
        convertToRecord(fieldsObservable, firebase)(makeRecord.tupled)
      }
      def set(firebase: Firebase, record: Record) = {
        parallel(
          setField(field1, record, firebase),
          setField(field2, record, firebase)
        )
      }
    }

  private def parallel(futures: Future[Unit]*): Future[Unit] =
    Future.traverse(futures)(identity).map(_ => ())

  def record[Field1, Field2, Field3, Record](makeRecord: (Stored[Field1], Stored[Field2], Stored[Field3]) => Record)(
    field1: Field[Field1, Record],
    field2: Field[Field2, Record],
    field3: Field[Field3, Record]
  ): Mapping[Record] =
    new Mapping[Record] {
      def observe(firebase: Firebase) = {
        val field1Observable = fieldMapping(field1).observe(firebase)
        val field2Observable = fieldMapping(field2).observe(firebase)
        val field3Observable = fieldMapping(field3).observe(firebase)
        val fieldsObservable =
          field1Observable
            .combineLatest(field2Observable)
            .combineLatest(field3Observable)
            .map { case ((f1, f2), f3) => (f1, f2, f3) }
        convertToRecord(fieldsObservable, firebase)(makeRecord.tupled)
      }
      def set(firebase: Firebase, record: Record) = {
        parallel(
          setField(field1, record, firebase),
          setField(field2, record, firebase),
          setField(field3, record, firebase)
        )
      }
    }

  def record[Field1, Field2, Field3, Field4, Record](makeRecord: (Stored[Field1], Stored[Field2], Stored[Field3], Stored[Field4]) => Record)(
    field1: Field[Field1, Record],
    field2: Field[Field2, Record],
    field3: Field[Field3, Record],
    field4: Field[Field4, Record]
  ): Mapping[Record] =
    new Mapping[Record] {
      def observe(firebase: Firebase) = {
        val field1Observable = fieldMapping(field1).observe(firebase)
        val field2Observable = fieldMapping(field2).observe(firebase)
        val field3Observable = fieldMapping(field3).observe(firebase)
        val field4Observable = fieldMapping(field4).observe(firebase)
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
          setField(field1, record, firebase),
          setField(field2, record, firebase),
          setField(field3, record, firebase),
          setField(field4, record, firebase)
        )
      }
    }

  def choice[Alternative1 <: Choice : ClassTag, Alternative2 <: Choice : ClassTag, Choice](
    alternative1: Alternative[Alternative1],
    alternative2: Alternative[Alternative2]
  ): Mapping[Choice] =
    new Mapping[Choice] {
      def observe(firebase: Firebase) = {
        val typeNameObservable = string.observe(typeNameChild(firebase))
        typeNameObservable.switchMap(remoteTypeName => {
          remoteTypeName.value match {
            case Right(alternative1.typeName) => alternativeObserve(alternative1, firebase)
            case Right(alternative2.typeName) => alternativeObserve(alternative2, firebase)
            case Left(error) => Observable.pure(Remote(firebase, Left(error)))
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
    alternative.getMapping().observe(valueChild(firebase)).map(stored => {
      Remote(firebase, stored.value) // trace the original Firebase since mapping.set works for it correctly
    })

  def choice[Alternative1 <: Choice : ClassTag, Alternative2 <: Choice : ClassTag, Alternative3 <: Choice : ClassTag, Choice](
    alternative1: Alternative[Alternative1],
    alternative2: Alternative[Alternative2],
    alternative3: Alternative[Alternative3]
  ): Mapping[Choice] =
    new Mapping[Choice] {
      def observe(firebase: Firebase) = {
        val typeNameObservable = string.observe(typeNameChild(firebase))
        typeNameObservable.switchMap(remoteTypeName => {
          remoteTypeName.value match {
            case Right(alternative1.typeName) => alternativeObserve(alternative1, firebase)
            case Right(alternative2.typeName) => alternativeObserve(alternative2, firebase)
            case Right(alternative3.typeName) => alternativeObserve(alternative3, firebase)
            case Left(error) => Observable.pure(Remote(firebase, Left(error)))
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

  def choice[Alternative1 <: Choice : ClassTag, Alternative2 <: Choice : ClassTag, Alternative3 <: Choice : ClassTag, Alternative4 <: Choice : ClassTag, Choice](
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
            case Right(alternative1.typeName) => alternativeObserve(alternative1, firebase)
            case Right(alternative2.typeName) => alternativeObserve(alternative2, firebase)
            case Right(alternative3.typeName) => alternativeObserve(alternative3, firebase)
            case Right(alternative4.typeName) => alternativeObserve(alternative4, firebase)
            case Left(error) => Observable.pure(Remote(firebase, Left(error)))
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

  def valueChild(firebase: Firebase) = {
    firebase.child("value")
  }

  private def alternativeSet[T](alternative: Alternative[T], firebase: Firebase, value: T) =
    parallel(
      typeNameChild(firebase).set(alternative.typeName),
      alternative.getMapping().set(valueChild(firebase), value)
    )

  type Many[+T] =
    List[Stored[T]]

  def list[T](elementMapping: Mapping[T]): Mapping[Many[T]] =
    new Mapping[Many[T]] {
      def observe(firebase: Firebase) = {
        val listObservable = observeRaw(firebase)
        listObservable.switchMap(snapshot => {
          val children = getChildren(snapshot)
          val updatesByChild = children.map(child =>
            elementMapping.observe(child).map(elementValue => (child.toString, elementValue))
          )
          val elementsByChild =
            if (updatesByChild.isEmpty) {
              Observable.pure(Map[String, Stored[T]]())
            } else {
              Observable.merge(updatesByChild:_*).scan(Map[String, Stored[T]]())(_ + _)
            }
          elementsByChild.map(elementMap =>
            Remote(firebase, Right(elementMap.toList.sortBy(entry => entry._1).map(entry => entry._2)))
          )
        })
      }

      def set(firebase: Firebase, elements: Many[T]) = {
        firebase.remove()
        val futures = elements.map(element =>
          element.value.right.toOption
            .map(value => elementMapping.set(element.firebase, value))
            .getOrElse(Future())
        )
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