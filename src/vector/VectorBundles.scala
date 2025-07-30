// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2024 OpenGPU Contributors

package ogpu.vector

import chisel3._
import chisel3.util._
import chisel3.experimental.SerializableModuleParameter

/** Vector Parameter
  *
  * Unified parameter class for all vector operations
  */
case class VectorParameter(
  threadNum: Int,
  warpNum:   Int,
  xLen:      Int)
    extends SerializableModuleParameter

/** Vector Result Bundle
  *
  * Contains results from vector operations
  */
class VectorResultBundle(parameter: VectorParameter) extends Bundle {
  val warpID = UInt(log2Ceil(parameter.warpNum).W)
  val rd = UInt(5.W)
  val result = Vec(parameter.threadNum, UInt(parameter.xLen.W))
  val exception = Bool()
  val fflags = UInt(5.W)
}
