package sbtrelease

import sbt._
import Keys._
import complete.DefaultParsers._

object ReleasePlugin extends Plugin {
  object ReleaseKeys {
    lazy val snapshotDependencies = TaskKey[Seq[ModuleID]]("release-snapshot-dependencies")
    lazy val releaseProcess = SettingKey[Seq[ReleaseStep]]("release-process")
    lazy val releaseVersion = SettingKey[String => String]("release-release-version")
    lazy val nextVersion = SettingKey[String => String]("release-next-version")
    lazy val tagName = SettingKey[String]("release-tag-name")

    lazy val versionControlSystem = SettingKey[Option[Vcs]]("release-vcs")

    lazy val versions = AttributeKey[Versions]("release-versions")
    lazy val useDefaults = AttributeKey[Boolean]("release-use-defaults")
    lazy val skipTests = AttributeKey[Boolean]("release-skip-tests")

    private lazy val releaseCommandKey = "release"
    private val WithDefaults = "with-defaults"
    private val SkipTests = "skip-tests"
    private val releaseParser = (Space ~> WithDefaults | Space ~> SkipTests).*

    val releaseCommand: Command = Command(releaseCommandKey)(_ => releaseParser) { (st, args) =>
      val extracted = Project.extract(st)
      val releaseParts = extracted.get(releaseProcess)

      val startState = st
        .put(useDefaults, args.contains(WithDefaults))
        .put(skipTests, args.contains(SkipTests))

      val initialChecks = releaseParts.map(_.check)
      val process = releaseParts.map(_.action)

      initialChecks.foreach(_(startState))
      Function.chain(process)(startState)
    }
  }

  import ReleaseKeys._
  import ReleaseStateTransformations._

  lazy val releaseSettings = Seq[Setting[_]](
    snapshotDependencies <<= (managedClasspath in Runtime) map { cp: Classpath =>
      val moduleIds = cp.flatMap(_.get(moduleID.key))
      val snapshots = moduleIds.filter(m => m.isChanging || m.revision.endsWith("-SNAPSHOT"))
      snapshots
    },

    releaseVersion := { ver => Version(ver).map(_.withoutQualifier.string).getOrElse(versionFormatError) },
    nextVersion := { ver => Version(ver).map(_.bumpMinor.asSnapshot.string).getOrElse(versionFormatError) },

    tagName <<= (version in ThisBuild) (v => "v" + v),

    versionControlSystem <<= (baseDirectory)(Vcs.detect(_)),

    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runTest,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      publishArtifacts,
      setNextVersion,
      commitNextVersion,
      pushChanges
    ),

    commands += releaseCommand
  )

  lazy val extraReleaseCommands = {
    import ExtraReleaseCommands._

    Seq[Setting[_]](
      commands ++= Seq(
        checkSnapshotDependenciesCommand,
        inquireVersionsCommand,
        setReleaseVersionCommand,
        setNextVersionCommand,
        initialVcsChecksCommand,
        commitReleaseVersionCommand,
        commitNextVersionCommand,
        tagReleaseCommand,
        pushChangesCommand
      )
    )
  }
}


case class ReleaseStep(action: State => State, check: State => State = identity)

object ReleaseStep {
  implicit def func2ReleasePart(f: State => State): ReleaseStep = ReleaseStep(f)

  implicit def releasePart2Func(rp: ReleaseStep): State=>State = rp.action
}
