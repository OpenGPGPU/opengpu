// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2022 Jiuyang Liu <liu@jiuyang.me>
// SPDX-FileCopyrightText: 2024 DonaldDuck <vivazsj@gmail.com>

import mill._
import mill.scalalib._
import mill.scalalib.publish._
import mill.scalalib.scalafmt._
import mill.scalalib.api.ZincWorkerUtil
import mill.scalalib.scalafmt._
import $ivy.`com.goyeau::mill-scalafix::0.4.2`
import com.goyeau.mill.scalafix.ScalafixModule

import mill.util.Jvm
import coursier.maven.MavenRepository
import $file.depends.chisel.build
import $file.depends.arithmetic.common
import $file.depends.`chisel-interface`.common
import $file.depends.`hardfloat`.common
import $file.depends.`rvdecoderdb`.common
import $file.common

object v {
  val scala    = "2.13.15"
  val mainargs = ivy"com.lihaoyi::mainargs:0.5.0"
  val sourcecode = ivy"com.lihaoyi::sourcecode:0.3.1"
  val oslib    = ivy"com.lihaoyi::os-lib:0.9.1"
  val upickle  = ivy"com.lihaoyi::upickle:3.3.1"
  val spire    = ivy"org.typelevel::spire:latest.integration"
  val evilplot = ivy"io.github.cibotech::evilplot:latest.integration"
  val scalaReflect = ivy"org.scala-lang:scala-reflect:${scala}"
  val chiselPlugin = ivy"org.chipsalliance:::chisel-plugin:6.6.0"
}

object chisel extends Chisel

trait Chisel extends millbuild.depends.chisel.build.Chisel {
  def crossValue              = v.scala
  override def millSourcePath = os.pwd / "depends" / "chisel"
}

object arithmetic extends Arithmetic

trait Arithmetic extends millbuild.depends.arithmetic.common.ArithmeticModule {
  override def millSourcePath = os.pwd / "depends" / "arithmetic" / "arithmetic"
  def scalaVersion            = T(v.scala)

  def chiselModule    = Some(chisel)
  def chiselPluginJar = T(Some(chisel.pluginModule.jar()))
  def chiselIvy       = None
  def chiselPluginIvy = None

  def spireIvy:    T[Dep] = v.spire
  def evilplotIvy: T[Dep] = v.evilplot
}

object axi4 extends AXI4

trait AXI4 extends millbuild.depends.`chisel-interface`.common.AXI4Module {
  override def millSourcePath = os.pwd / "depends" / "chisel-interface" / "axi4"
  def scalaVersion            = v.scala

  def mainargsIvy = v.mainargs

  def chiselModule    = Some(chisel)
  def chiselPluginJar = T(Some(chisel.pluginModule.jar()))
  def chiselIvy       = None
  def chiselPluginIvy = None
}

object hardfloat extends Hardfloat

trait Hardfloat extends millbuild.depends.`hardfloat`.common.HardfloatModule {
  override def millSourcePath = os.pwd / "depends" / "hardfloat" / "hardfloat"
  def scalaVersion            = T(v.scala)

  def chiselModule    = Some(chisel)
  def chiselPluginJar = T(Some(chisel.pluginModule.jar()))
  def chiselIvy       = None
  def chiselPluginIvy = None
}

object rvdecoderdb extends RVDecoderDB

trait RVDecoderDB extends millbuild.depends.rvdecoderdb.common.RVDecoderDBJVMModule with ScalaModule {
  def scalaVersion            = T(v.scala)
  def osLibIvy                = v.oslib
  def upickleIvy              = v.upickle
  override def millSourcePath = os.pwd / "depends" / "rvdecoderdb" / "rvdecoderdb"
}

object dwbb extends DWBB

trait DWBB extends millbuild.depends.`chisel-interface`.common.DWBBModule {
  override def millSourcePath = os.pwd / "depends" / "chisel-interface" / "dwbb"
  def scalaVersion            = v.scala

  def mainargsIvy = v.mainargs

  def chiselModule    = Some(chisel)
  def chiselPluginJar = T(Some(chisel.pluginModule.jar()))
  def chiselIvy       = None
  def chiselPluginIvy = None
}


object stdlib extends Stdlib

trait Stdlib extends millbuild.common.StdlibModule with ScalafmtModule {
  override def millSourcePath = os.pwd / "depends" / "t1" / "stdlib"
  def scalaVersion = v.scala

  def mainargsIvy = v.mainargs

  def dwbbModule: ScalaModule = dwbb

  def chiselModule    = Some(chisel)
  def chiselPluginJar = T(Some(chisel.pluginModule.jar()))
  def chiselIvy       = None
  def chiselPluginIvy = None
}

object rocketv extends Rocketv

trait Rocketv extends millbuild.common.RocketvModule with ScalafmtModule {
  override def millSourcePath = os.pwd / "depends" / "t1" / "rocketv"
  def scalaVersion = T(v.scala)

  def arithmeticModule  = arithmetic
  def axi4Module        = axi4
  def hardfloatModule   = hardfloat
  def stdlibModule      = stdlib
  def rvdecoderdbModule = rvdecoderdb
  def riscvOpcodesPath  = T.input(PathRef(os.pwd / "depends" / "riscv-opcodes"))

  def chiselModule    = Some(chisel)
  def chiselPluginJar = T(Some(chisel.pluginModule.jar()))
  def chiselIvy       = None
  def chiselPluginIvy = None
}


object t1 extends T1

trait T1 extends millbuild.common.T1Module with ScalafmtModule {
  override def millSourcePath = os.pwd / "depends" / "t1" / "t1"
  def scalaVersion = T(v.scala)

  def arithmeticModule  = arithmetic
  def axi4Module        = axi4
  def hardfloatModule   = hardfloat
  def rvdecoderdbModule = rvdecoderdb
  def stdlibModule      = stdlib
  def riscvOpcodesPath  = T.input(PathRef(os.pwd / "depends" / "riscv-opcodes"))

  def chiselModule    = Some(chisel)
  def chiselPluginJar = T(Some(chisel.pluginModule.jar()))
  def chiselIvy       = None
  def chiselPluginIvy = None
}

object fpuVerilogGen extends ScalaModule {
  def scalaVersion = v.scala
  override def sources = T.sources()

  override def compile = T {
    val makefileDir = os.pwd / "src" / "fpu"
    val outDir = T.dest
    os.makeDir.all(outDir)
    val result = os.proc("make", s"OUT_DIR=$outDir", "-C", makefileDir.toString).call()
    if (!os.exists(outDir / "combined.sv")) {
      throw new Exception("combined.sv not generated!")
    }
    super.compile()
  }
}

object ogpu extends OGPU

trait OGPU extends millbuild.common.OGPUModule
    with ScalafmtModule
    with SbtModule
    with ScalafixModule { // Add ScalafixModule
  override def millSourcePath = os.pwd
  def scalaVersion            = v.scala

  def arithmeticModule  = arithmetic
  def axi4Module        = axi4
  def hardfloatModule   = hardfloat
  def rvdecoderdbModule = rvdecoderdb
  def stdlibModule      = stdlib
  def T1Module          = t1
  def RocketvModule     = rocketv
  def fpuModule        = fpuVerilogGen

  def riscvOpcodesPath  = T.input(PathRef(os.pwd / "depends" / "riscv-opcodes"))

  def chiselModule    = Some(chisel)
  def chiselPluginJar = T(Some(chisel.pluginModule.jar()))
  def chiselIvy       = None
  def chiselPluginIvy = Some(v.chiselPlugin)

  override def sources = T.sources {
    super.sources() ++ Seq(PathRef(this.millSourcePath / "src"))
  }
  def lineCount = T {
    this.sources().filter(ref => os.exists(ref.path)).flatMap(ref => os.walk(ref.path)).filter(os.isFile).flatMap(os.read.lines).size
  }

  def printLineCount() = T.command {
    println(s"Lines of code(LOC): ${lineCount()} !!!")
  }
  
  def scalacOptions = Seq(
    "-Ywarn-unused",
    "-Ymacro-annotations",
    "-Wunused:imports"
  )

  object test extends SbtTests
    with TestModule.ScalaTest
    with ScalafmtModule
    with ScalafixModule {

    override def forkArgs = Seq("-Xmx6G", "-Xss256m")

    override def sources = T.sources {
      super.sources() ++ Seq(PathRef(this.millSourcePath / "tests"))
    }
    override def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"org.scalatest::scalatest:3.2.19"
    )

    def scalacOptions = Seq(
      "-Ywarn-unused",
      "-Ymacro-annotations",
      "-Wunused:imports"
    )
  }
}
