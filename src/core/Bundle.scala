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
  val pc = UInt(32.W)
  val vgprs = Vec(dimNum, UInt(dimWidth.W))
  val vgpr_num = UInt(2.W)
  val sgprs = Vec(16, UInt(xLen.W))
}

class StackData(
  threadNum: Int,
  paddrBits: Int)
    extends Bundle {
  val mask = Vec(threadNum, Bool())
  val pc = UInt(paddrBits.W)
  val orig_mask = Vec(threadNum, Bool())
}
