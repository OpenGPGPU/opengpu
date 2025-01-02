package ogpu.core

import chisel3._
import chisel3.util._

class CuTaskBundle(
  threadNum: Int,
  warpNum:   Int,
  dimNum:    Int,
  xLen:      Int)
    extends Bundle {}
