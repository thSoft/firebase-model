package hu.thsoft.firebasemodel

import scala.Left
import scala.Right
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.scalajs.js
import scala.scalajs.js.Any.fromFunction1
import scala.scalajs.js.Thenable.Implicits.thenable2future

import hu.thsoft.firebase.Firebase
import hu.thsoft.firebase.FirebaseDataSnapshot
import monix.reactive.Observable
import upickle.Js
import upickle.default.StringRW
import upickle.default.readJs
import upickle.default.writeJs

trait Versioned[T] {
  def versions: Observable[Version[T]]
  def set(value: T): Future[Version[T]]
  def getLatestStable: Observable[Version[T]]
  def setLatestStable(latestStable: VersionRef[T]): Future[Unit]
}

case class Version[T](
  versioned: Versioned[T],
  id: String,
  value: Either[Invalid, T],
  ref: VersionRef[T]
)

trait VersionRef[T] {
  def id: String
  def get: Future[Version[T]]
}

trait Codec[T] {
  def readJson(json: Js.Value): T
  def writeJson(value: T): Js.Value
  def typeName: String
}

object Versioned {

  def apply[T](firebase: Firebase, codec: Codec[T]): Versioned[T] = {
    lazy val versioned: Versioned[T] = new Versioned[T] {

      val versionsFirebase = firebase.child("versions")

      private def versionRef(versionId: String): VersionRef[T] = {
        new VersionRef[T] {
          override def id: String = versionId
          override def get: Future[Version[T]] = {
            val promise = Promise[Version[T]]
            versionsFirebase.child(versionId).once("value", ((snapshot: FirebaseDataSnapshot) => {
              promise.success(snapshotToVersion(snapshot))
              ()
            }))
            promise.future
          }
        }
      }

      val valueKey = "value"

      private def snapshotToVersion(snapshot: FirebaseDataSnapshot): Version[T] = {
        val valueJson = upickle.json.readJs(snapshot.child(valueKey).`val`)
        val value =
          valueJson match {
            case Js.Null => Left(Invalid(null, codec.typeName, new NullPointerException))
            case _ =>
              try {
                Right(codec.readJson(valueJson))
              } catch {
                case e: Throwable => Left(Invalid(valueJson, codec.typeName, e))
              }
          }
        val id = snapshot.key()
        Version(
          versioned = versioned,
          id = id,
          value = value,
          ref = versionRef(id)
        )
      }

      override def versions: Observable[Version[T]] = {
        Mapping.observeRaw(versionsFirebase, "child_added").map(snapshot => snapshotToVersion(snapshot))
      }

      override def set(value: T): Future[Version[T]] = {
        val newVersionFirebase = versionsFirebase.push(null)
        val newVersionId = newVersionFirebase.key()
        val valueJson = upickle.json.writeJs(codec.writeJson(value)).asInstanceOf[js.Any]
        newVersionFirebase.child(valueKey).set(valueJson).flatMap(_ => versionRef(newVersionId).get)
      }

      val latestStableFirebase = firebase.child("latestStable")

      override def getLatestStable: Observable[Version[T]] = {
        Mapping.observeRaw(latestStableFirebase).flatMap(snapshot => {
          val json = upickle.json.readJs(snapshot.`val`)
          json match {
            case Js.Str(id) => Observable.fromFuture(versionRef(id).get)
            case _ => Observable.empty
          }
        })
      }

      override def setLatestStable(latestStable: VersionRef[T]): Future[Unit] = {
        val json = upickle.json.writeJs(writeJs(latestStable.id)).asInstanceOf[js.Any]
        latestStableFirebase.set(json)
      }

    }
    versioned
  }

  def create[T](parentFirebase: Firebase, codec: Codec[T]): Versioned[T] = {
    val newFirebase = parentFirebase.push(upickle.json.writeJs(Js.Null).asInstanceOf[js.Any])
    Versioned(newFirebase, codec)
  }

}

object Codec {

  lazy val int: Codec[Int] =
    new Codec[Int] {

      override def readJson(json: Js.Value): Int = readJs[Int](json)

      override def writeJson(value: Int): Js.Value = writeJs[Int](value)

      override def typeName: String = "Integer"

    }

}