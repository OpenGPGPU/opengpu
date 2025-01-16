package millbuild

import _root_.mill.runner.MillBuildRootModule

object MiscInfo_common {
  implicit lazy val millBuildRootModuleInfo: _root_.mill.runner.MillBuildRootModule.Info = _root_.mill.runner.MillBuildRootModule.Info(
    Vector("/home/runner/work/opengpu/opengpu/out/mill-launcher/0.11.6.jar").map(_root_.os.Path(_)),
    _root_.os.Path("/home/runner/work/opengpu/opengpu"),
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
class common extends _root_.mill.main.RootModule.Foreign(Some(_root_.mill.define.Segments.labels("foreign-modules", "common"))) {

//MILL_ORIGINAL_FILE_PATH=/home/runner/work/opengpu/opengpu/common.sc
//MILL_USER_CODE_START_MARKER
// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2022 Jiuyang Liu <liu@jiuyang.me>
// SPDX-FileCopyrightText: 2024 DonaldDuck <vivazsj@gmail.com>

import mill._
import mill.scalalib._

trait HasChisel extends ScalaModule {
  // Define these for building chisel from source
  def chiselModule: Option[ScalaModule]

  override def moduleDeps = super.moduleDeps ++ chiselModule

  def chiselPluginJar: T[Option[PathRef]]

  override def scalacOptions = T(
    super.scalacOptions() ++ chiselPluginJar().map(path => s"-Xplugin:${path.path}") ++ Seq("-Ymacro-annotations")
  )

  override def scalacPluginClasspath: T[Agg[PathRef]] = T(super.scalacPluginClasspath() ++ chiselPluginJar())

  // Define these for building chisel from ivy
  def chiselIvy: Option[Dep]

  override def ivyDeps = T(super.ivyDeps() ++ chiselIvy)

  def chiselPluginIvy: Option[Dep]

  override def scalacPluginIvyDeps: T[Agg[Dep]] = T(
    super.scalacPluginIvyDeps() ++ chiselPluginIvy.map(Agg(_)).getOrElse(Agg.empty[Dep])
  )
}

trait ConfigGenModule extends ScalaModule {
  def t1Module: ScalaModule
  def moduleDeps = super.moduleDeps ++ Seq(t1Module)
  def mainargsIvy: Dep
  override def ivyDeps = T(super.ivyDeps() ++ Seq(mainargsIvy))
}

trait StdlibModule extends ScalaModule with HasChisel {
  def dwbbModule: ScalaModule

  def moduleDeps = super.moduleDeps ++ Seq(dwbbModule)
}

trait HasRVDecoderDB extends ScalaModule {
  def rvdecoderdbModule: ScalaModule
  def riscvOpcodesPath:  T[PathRef]
  def moduleDeps = super.moduleDeps ++ Seq(rvdecoderdbModule)
  def riscvOpcodesTar:    T[PathRef]      = T {
    val tmpDir = os.temp.dir()
    os.makeDir(tmpDir / "unratified")
    os.walk(riscvOpcodesPath().path)
      .filter(f =>
        f.baseName.startsWith("rv128_") ||
          f.baseName.startsWith("rv64_") ||
          f.baseName.startsWith("rv32_") ||
          f.baseName.startsWith("rv_") ||
          f.ext == "csv"
      )
      .groupBy(_.segments.contains("unratified"))
      .map {
        case (true, fs)  => fs.map(os.copy.into(_, tmpDir / "unratified"))
        case (false, fs) => fs.map(os.copy.into(_, tmpDir))
      }
    os.proc("tar", "cf", T.dest / "riscv-opcodes.tar", ".").call(tmpDir)
    PathRef(T.dest)
  }
  override def resources: T[Seq[PathRef]] = super.resources() ++ Some(riscvOpcodesTar())
}

trait RocketvModule extends ScalaModule with HasChisel with HasRVDecoderDB {
  def arithmeticModule: ScalaModule
  def hardfloatModule:  ScalaModule
  def axi4Module:       ScalaModule
  def stdlibModule:     ScalaModule
  def moduleDeps = super.moduleDeps ++ Seq(arithmeticModule, hardfloatModule, axi4Module, stdlibModule)
}

trait T1Module extends ScalaModule with HasChisel with HasRVDecoderDB {
  def arithmeticModule: ScalaModule
  def hardfloatModule:  ScalaModule
  def axi4Module:       ScalaModule
  def stdlibModule:     ScalaModule
  def moduleDeps = super.moduleDeps ++ Seq(arithmeticModule, hardfloatModule, axi4Module, stdlibModule)
}

trait OGPUModule extends ScalaModule with HasChisel with HasRVDecoderDB {
  def arithmeticModule: ScalaModule
  def hardfloatModule:  ScalaModule
  def axi4Module:       ScalaModule
  def stdlibModule:     ScalaModule
  def T1Module:         ScalaModule
  def RocketvModule:    ScalaModule
  def cdeModule:        ScalaModule
  def diplomacyModule:        ScalaModule
  def rocketchipModule:        ScalaModule

  def moduleDeps = super.moduleDeps ++ Seq(arithmeticModule, hardfloatModule, axi4Module, stdlibModule, T1Module, cdeModule, diplomacyModule, rocketchipModule, RocketvModule)
}

}