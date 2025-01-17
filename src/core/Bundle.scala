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
  val sgpr_num = UInt(4.W)
}

class StackData(
  threadNum: Int,
  addrBits:  Int)
    extends Bundle {
  // diverge mask
  val mask = Vec(threadNum, Bool())
  // diverge pc
  val pc = UInt(addrBits.W)
  // original mask before diverge
  val orig_mask = Vec(threadNum, Bool())
}

class CommitSData(
  xLen:      Int,
  addrWidth: Int,
  warpNum:   Int,
  regNum:    Int)
    extends Bundle {
  val regIDWidth = log2Ceil(regNum)

  val wid = UInt(log2Ceil(warpNum).W)
  val mask = Bool()
  val pc = UInt(addrWidth.W)
  val rd = UInt(regIDWidth.W)
  val data = UInt(xLen.W)
}
