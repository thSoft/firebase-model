lazy val root = project.in(file(".")).
  enablePlugins(ScalaJSPlugin)

name := "Firebase-Model"

normalizedName := "firebase-model"

version := "0.0.0"

organization := "hu.thsoft"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "hu.thsoft" %%% "firebase-scalajs" % "2.4.1",
  "io.monix" %%% "monix" % "2.0-M2",
  "com.lihaoyi" %%% "upickle" % "0.3.8"
)

EclipseKeys.withSource := true

licenses += ("MIT License", url("http://www.opensource.org/licenses/mit-license.php"))

val repo = "thsoft/firebase-model"

scmInfo := Some(ScmInfo(
  url(s"https://github.com/$repo"),
  s"scm:git:git@github.com:$repo.git",
  Some(s"scm:git:git@github.com:$repo.git")))

publishMavenStyle := true

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

pomIncludeRepository := { _ => false }
