package millbuild.depends.`chisel-interface`

import _root_.mill.runner.MillBuildRootModule

object MiscInfo_common {
  implicit lazy val millBuildRootModuleInfo: _root_.mill.runner.MillBuildRootModule.Info = _root_.mill.runner.MillBuildRootModule.Info(
    Vector("/home/runner/work/opengpu/opengpu/out/mill-launcher/0.11.6.jar").map(_root_.os.Path(_)),
    _root_.os.Path("/home/runner/work/opengpu/opengpu/depends/chisel-interface"),
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
class common extends _root_.mill.main.RootModule.Foreign(Some(_root_.mill.define.Segments.labels("foreign-modules", "depends", "chisel-interface", "common"))) {

//MILL_ORIGINAL_FILE_PATH=/home/runner/work/opengpu/opengpu/depends/chisel-interface/common.sc
//MILL_USER_CODE_START_MARKER
import mill._
import mill.scalalib._

trait HasChisel extends ScalaModule {
  // Define these for building chisel from source
  def chiselModule: Option[ScalaModule] = None

  override def moduleDeps = super.moduleDeps ++ chiselModule

  def chiselPluginJar: T[Option[PathRef]] = None

  override def scalacOptions = T(
    super.scalacOptions() ++ chiselPluginJar().map(path =>
      s"-Xplugin:${path.path}"
    ) ++ Seq("-Ymacro-annotations")
  )

  override def scalacPluginClasspath: T[Agg[PathRef]] = T(
    super.scalacPluginClasspath() ++ chiselPluginJar()
  )

  // Define these for building chisel from ivy
  def chiselIvy: Option[Dep] = None

  override def ivyDeps = T(super.ivyDeps() ++ chiselIvy)

  def chiselPluginIvy: Option[Dep] = None

  override def scalacPluginIvyDeps: T[Agg[Dep]] = T(
    super.scalacPluginIvyDeps() ++ chiselPluginIvy
      .map(Agg(_))
      .getOrElse(Agg.empty[Dep])
  )
}

trait ChiselInterfaceModule extends HasChisel {
  // Use for elaboration.
  def mainargsIvy: Dep
  override def ivyDeps = T(super.ivyDeps() ++ Some(mainargsIvy))
}

trait DWBBModule extends ChiselInterfaceModule

trait AXI4Module extends ChiselInterfaceModule

trait JTAGModule extends ChiselInterfaceModule

}