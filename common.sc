// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2022 Jiuyang Liu <liu@jiuyang.me>

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

  def moduleDeps = super.moduleDeps ++ Seq(arithmeticModule, hardfloatModule, axi4Module, stdlibModule, T1Module, RocketvModule)
}
