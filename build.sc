// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2022 Jiuyang Liu <liu@jiuyang.me>

import mill._
import mill.scalalib._
import mill.define.{Command, TaskModule}
import mill.scalalib.publish._
import mill.scalalib.scalafmt._
import mill.scalalib.TestModule.Utest
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
  val oslib    = ivy"com.lihaoyi::os-lib:0.9.1"
  val upickle  = ivy"com.lihaoyi::upickle:3.3.1"
  val spire    = ivy"org.typelevel::spire:latest.integration"
  val evilplot = ivy"io.github.cibotech::evilplot:latest.integration"
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
  def scalaVersion = v.scala

  def mainargsIvy = v.mainargs

  def dwbbModule: ScalaModule = dwbb

  def chiselModule    = Some(chisel)
  def chiselPluginJar = T(Some(chisel.pluginModule.jar()))
  def chiselIvy       = None
  def chiselPluginIvy = None
}

object t1 extends T1

trait T1 extends millbuild.common.T1Module with ScalafmtModule {
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
