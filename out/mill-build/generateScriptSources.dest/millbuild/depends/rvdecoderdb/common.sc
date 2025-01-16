package millbuild.depends.rvdecoderdb

import _root_.mill.runner.MillBuildRootModule

object MiscInfo_common {
  implicit lazy val millBuildRootModuleInfo: _root_.mill.runner.MillBuildRootModule.Info = _root_.mill.runner.MillBuildRootModule.Info(
    Vector("/home/runner/work/opengpu/opengpu/out/mill-launcher/0.11.6.jar").map(_root_.os.Path(_)),
    _root_.os.Path("/home/runner/work/opengpu/opengpu/depends/rvdecoderdb"),
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
class common extends _root_.mill.main.RootModule.Foreign(Some(_root_.mill.define.Segments.labels("foreign-modules", "depends", "rvdecoderdb", "common"))) {

//MILL_ORIGINAL_FILE_PATH=/home/runner/work/opengpu/opengpu/depends/rvdecoderdb/common.sc
//MILL_USER_CODE_START_MARKER
// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2022 Jiuyang Liu <liu@jiuyang.me>

import mill._
import mill.scalalib._
import mill.scalajslib._

trait RVDecoderDBJVMModule extends ScalaModule {
  override def sources: T[Seq[PathRef]] = T.sources { super.sources() ++ Some(PathRef(millSourcePath / "jvm" / "src"))  }
  def osLibIvy: Dep
  def upickleIvy: Dep
  override def ivyDeps = super.ivyDeps() ++ Some(osLibIvy) ++ Some(upickleIvy)
}

trait HasRVDecoderDBResource extends ScalaModule {
  def riscvOpcodesPath: T[Option[PathRef]] = T(None)
  def riscvOpcodesTar: T[Option[PathRef]] = T {
    riscvOpcodesPath().map { riscvOpcodesPath =>
      val tmpDir = os.temp.dir()
      os.makeDir(tmpDir / "unratified")
      os.walk(riscvOpcodesPath.path)
        .filter(f =>
          f.baseName.startsWith("rv128_") ||
            f.baseName.startsWith("rv64_") ||
            f.baseName.startsWith("rv32_") ||
            f.baseName.startsWith("rv_") ||
            f.ext == "csv"
        ).groupBy(_.segments.contains("unratified")).map {
            case (true, fs) => fs.map(os.copy.into(_, tmpDir / "unratified"))
            case (false, fs) => fs.map(os.copy.into(_, tmpDir))
          }
      os.proc("tar", "cf", T.dest / "riscv-opcodes.tar", ".").call(tmpDir)
      PathRef(T.dest)
    }
  }
  override def resources: T[Seq[PathRef]] = super.resources() ++ riscvOpcodesTar()
}

trait RVDecoderDBJVMTestModule extends HasRVDecoderDBResource with ScalaModule {
  override def sources: T[Seq[PathRef]] = T.sources { super.sources() ++ Some(PathRef(millSourcePath / "jvm" / "src"))  }
  def dut: RVDecoderDBJVMModule
  override def moduleDeps = super.moduleDeps ++ Some(dut)
}

trait RVDecoderDBJSModule extends ScalaJSModule {
  override def sources: T[Seq[PathRef]] = T.sources { super.sources() ++ Some(PathRef(millSourcePath / "js" / "src"))  }
  def upickleIvy: Dep
  override def ivyDeps = super.ivyDeps() ++ Some(upickleIvy)
}

trait RVDecoderDBTestJSModule extends ScalaJSModule {
  override def sources: T[Seq[PathRef]] = T.sources { super.sources() ++ Some(PathRef(millSourcePath / "js" / "src"))  }
  def dut: RVDecoderDBJSModule
  override def moduleDeps = super.moduleDeps ++ Some(dut)
}

}