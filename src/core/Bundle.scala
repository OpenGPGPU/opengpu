package ogpu.core

import chisel3._
import chisel3.util._

class CuTaskBundle(
  threadNum: Int,
  warpNum:   Int,
  dimNum:    Int,
  xLen:      Int,
  dimWidth:  Int = 16)
    extends Bundle {
  val mask = Vec(threadNum, Bool())
  val dim = Vec(dimNum, UInt(dimWidth.W))
  val pc = UInt(32.W)
}
