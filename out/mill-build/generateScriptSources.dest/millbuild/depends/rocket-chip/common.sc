package millbuild.depends.`rocket-chip`

import _root_.mill.runner.MillBuildRootModule

object MiscInfo_common {
  implicit lazy val millBuildRootModuleInfo: _root_.mill.runner.MillBuildRootModule.Info = _root_.mill.runner.MillBuildRootModule.Info(
    Vector("/home/runner/work/opengpu/opengpu/out/mill-launcher/0.11.6.jar").map(_root_.os.Path(_)),
    _root_.os.Path("/home/runner/work/opengpu/opengpu/depends/rocket-chip"),
    _root_.os.Path("/home/runner/work/opengpu/opengpu"),
    _root_.scala.Seq()
  )
  implicit lazy val millBaseModuleInfo: _root_.mill.main.RootModule.Info = _root_.mill.main.RootModule.Info(
    millBuildRootModuleInfo.projectRoot,
    _root_.mill.define.Discover[common]
  )
}
import MiscInfo_common.{millBuildRootModuleInfo, millBaseModuleInfo}
object common extends common
class common extends _root_.mill.main.RootModule.Foreign(Some(_root_.mill.define.Segments.labels("foreign-modules", "depends", "rocket-chip", "common"))) {

//MILL_ORIGINAL_FILE_PATH=/home/runner/work/opengpu/opengpu/depends/rocket-chip/common.sc
//MILL_USER_CODE_START_MARKER
import mill._
import mill.scalalib._

trait HasChisel
  extends ScalaModule {
  // Define these for building chisel from source
  def chiselModule: Option[ScalaModule]

  override def moduleDeps = super.moduleDeps ++ chiselModule

  def chiselPluginJar: T[Option[PathRef]]

  override def scalacOptions = T(super.scalacOptions() ++ chiselPluginJar().map(path => s"-Xplugin:${path.path}"))

  override def scalacPluginClasspath: T[Agg[PathRef]] = T(super.scalacPluginClasspath() ++ chiselPluginJar())

  // Define these for building chisel from ivy
  def chiselIvy: Option[Dep]

  override def ivyDeps = T(super.ivyDeps() ++ chiselIvy)

  def chiselPluginIvy: Option[Dep]

  override def scalacPluginIvyDeps: T[Agg[Dep]] = T(super.scalacPluginIvyDeps() ++ chiselPluginIvy.map(Agg(_)).getOrElse(Agg.empty[Dep]))
}

trait MacrosModule
  extends ScalaModule {

  def scalaReflectIvy: Dep

  override def ivyDeps = T(super.ivyDeps() ++ Some(scalaReflectIvy))
}


trait RocketChipModule
  extends HasChisel {
  override def mainClass = T(Some("freechips.rocketchip.diplomacy.Main"))

  def macrosModule: MacrosModule

  // should be hardfloat/common.sc#HardfloatModule
  def hardfloatModule: ScalaModule

  def diplomacyModule: ScalaModule

  def mainargsIvy: Dep

  def json4sJacksonIvy: Dep

  override def moduleDeps = super.moduleDeps ++ Seq(macrosModule, hardfloatModule, diplomacyModule)

  override def ivyDeps = T(
    super.ivyDeps() ++ Agg(
      mainargsIvy,
      json4sJacksonIvy,
    )
  )
}

}