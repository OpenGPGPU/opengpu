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
