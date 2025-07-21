package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.SerializableModule
import chisel3.experimental.hierarchy.instantiable

class BranchResultBundle(parameter: OGPUDecoderParameter) extends Bundle {
  val pc = UInt(parameter.xLen.W)
  val wid = UInt(log2Ceil(parameter.warpNum).W)
}

class ScalarBranchInterface(parameter: OGPUDecoderParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val branchInfo = Flipped(DecoupledIO(new BranchInfoBundle(parameter)))
  val branchResult = DecoupledIO(new BranchResultBundle(parameter))
}

@instantiable
class ScalarBranch(val parameter: OGPUDecoderParameter)
    extends FixedIORawModule(new ScalarBranchInterface(parameter))
    with SerializableModule[OGPUDecoderParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // 默认输出无效
  io.branchInfo.ready := false.B
  io.branchResult.valid := false.B
  io.branchResult.bits := 0.U.asTypeOf(io.branchResult.bits)

  when(io.branchInfo.valid) {
    val isJal = io.branchInfo.bits.branch.isJal
    val isJalr = io.branchInfo.bits.branch.isJalr
    val isBranch = io.branchInfo.bits.branch.isBranch

    val op1 = Mux(isJal, io.branchInfo.bits.pc,
              Mux(isJalr, io.branchInfo.bits.rs1Data, io.branchInfo.bits.pc))
    val op2 = Mux(isJal || isJalr, io.branchInfo.bits.imm, 4.U)
    val target = op1 + op2

    io.branchResult.valid := true.B
    io.branchInfo.ready := io.branchResult.ready
    io.branchResult.bits.wid := io.branchInfo.bits.warpID
    io.branchResult.bits.pc := target
  }
}
