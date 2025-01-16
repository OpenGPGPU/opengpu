package millbuild

import _root_.mill.runner.MillBuildRootModule

object MiscInfo_build {
  implicit lazy val millBuildRootModuleInfo: _root_.mill.runner.MillBuildRootModule.Info = _root_.mill.runner.MillBuildRootModule.Info(
    Vector("/home/runner/work/opengpu/opengpu/out/mill-launcher/0.11.6.jar").map(_root_.os.Path(_)),
    _root_.os.Path("/home/runner/work/opengpu/opengpu"),
    _root_.os.Path("/home/runner/work/opengpu/opengpu"),
    _root_.scala.Seq()
  )
  implicit lazy val millBaseModuleInfo: _root_.mill.main.RootModule.Info = _root_.mill.main.RootModule.Info(
    millBuildRootModuleInfo.projectRoot,
    _root_.mill.define.Discover[build]
  )
}
import MiscInfo_build.{millBuildRootModuleInfo, millBaseModuleInfo}
object build extends build
class build extends _root_.mill.main.RootModule {

//MILL_ORIGINAL_FILE_PATH=/home/runner/work/opengpu/opengpu/build.sc
//MILL_USER_CODE_START_MARKER
// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2022 Jiuyang Liu <liu@jiuyang.me>
// SPDX-FileCopyrightText: 2024 DonaldDuck <vivazsj@gmail.com>

// import mill._
// import scalalib._
// import scalalib.scalafmt._
// import mill.scalalib.TestModule.ScalaTest
import mill._
import mill.scalalib._
import mill.scalalib.publish._
import mill.scalalib.scalafmt._

// import mill.scalalib._
// import mill.define.{Command, TaskModule}
// import mill.scalalib.publish._
// import mill.scalalib.scalafmt._
import mill.util.Jvm
import coursier.maven.MavenRepository
import millbuild.depends.chisel.build
import millbuild.depends.arithmetic.common
import millbuild.depends.`chisel-interface`.common
import millbuild.depends.hardfloat.common
import millbuild.depends.rvdecoderdb.common
import millbuild.depends.`rocket-chip`.common
import millbuild.depends.cde.common
import millbuild.depends.diplomacy.common
import _root_._

object v {
  val scala    = "2.13.15"
  val mainargs = ivy"com.lihaoyi::mainargs:0.5.0"
  val sourcecode = ivy"com.lihaoyi::sourcecode:0.3.1"
  val oslib    = ivy"com.lihaoyi::os-lib:0.9.1"
  val upickle  = ivy"com.lihaoyi::upickle:3.3.1"
  val spire    = ivy"org.typelevel::spire:latest.integration"
  val evilplot = ivy"io.github.cibotech::evilplot:latest.integration"
  val scalaReflect = ivy"org.scala-lang:scala-reflect:${scala}"
}

object chisel extends Chisel

trait Chisel extends millbuild.depends.chisel.build.Chisel {
  def crossValue              = v.scala
  override def millSourcePath = os.pwd / "depends" / "chisel"
}

object macros extends Macros

trait Macros
  extends millbuild.depends.`rocket-chip`.common.MacrosModule
    with ScalaModule {

  def scalaVersion: T[String] = T(v.scala)

  def scalaReflectIvy = v.scalaReflect
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

object cde extends CDE

trait CDE
  extends millbuild.depends.cde.common.CDEModule  with ScalaModule {
  override def millSourcePath = os.pwd / "depends" / "cde" / "cde"

  def scalaVersion: T[String] = T(v.scala)

}

object diplomacy extends Diplomacy

trait Diplomacy
    extends millbuild.depends.diplomacy.common.DiplomacyModule {

  override def scalaVersion: T[String] = T(v.scala)

  override def millSourcePath = os.pwd / "depends" / "diplomacy" / "diplomacy"
  def sourcecodeIvy = v.sourcecode


  def chiselModule    = Some(chisel)
  def chiselPluginJar = T(Some(chisel.pluginModule.jar()))
  def chiselIvy       = None
  def chiselPluginIvy = None

  def cdeModule = cde
}

object rocketchip extends RocketChip

trait RocketChip
  extends millbuild.depends.`rocket-chip`.common.RocketChipModule
    with SbtModule {
  def scalaVersion: T[String] = T(v.scala)
  def sourcecodeIvy = v.sourcecode
  def mainargsIvy = v.mainargs

  override def millSourcePath = os.pwd / "depends" / "rocket-chip"

  def chiselModule    = Some(chisel)
  def chiselPluginJar = T(Some(chisel.pluginModule.jar()))
  def chiselIvy       = None
  def chiselPluginIvy = None

  def hardfloatModule = hardfloat

  def cdeModule = cde

  def macrosModule = macros

  def diplomacyModule = diplomacy

  def diplomacyIvy = None


  def json4sJacksonIvy = ivy"org.json4s::json4s-jackson:4.0.5"
}

object ogpu extends OGPU

trait OGPU extends millbuild.common.OGPUModule with ScalafmtModule with SbtModule {
  override def millSourcePath = os.pwd
  def scalaVersion            = v.scala

  def arithmeticModule  = arithmetic
  def axi4Module        = axi4
  def hardfloatModule   = hardfloat
  def rvdecoderdbModule = rvdecoderdb
  def stdlibModule      = stdlib
  def T1Module          = t1
  def RocketvModule     = rocketv
  def cdeModule         = cde
  def diplomacyModule   = diplomacy
  def rocketchipModule  = rocketchip

  def riscvOpcodesPath  = T.input(PathRef(os.pwd / "depends" / "riscv-opcodes"))

  def chiselModule    = Some(chisel)
  def chiselPluginJar = T(Some(chisel.pluginModule.jar()))
  def chiselIvy       = None
  def chiselPluginIvy = None

  override def sources = T.sources {
    super.sources() ++ Seq(PathRef(this.millSourcePath / "src"))
  }
  def lineCount = T {
    this.sources().filter(ref => os.exists(ref.path)).flatMap(ref => os.walk(ref.path)).filter(os.isFile).flatMap(os.read.lines).size
  }

  def printLineCount() = T.command {
    println(s"Lines of code(LOC): ${lineCount()} !!!")
  }

  object test extends SbtModuleTests
    with TestModule.ScalaTest
    with ScalafmtModule {

    override def forkArgs = Seq("-Xmx8G", "-Xss256m")

    override def sources = T.sources {
      super.sources() ++ Seq(PathRef(this.millSourcePath / "test"))
    }
    override def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"org.scalatest::scalatest:3.2.17"
    )
  }
}

}