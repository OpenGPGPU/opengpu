package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.SerializableModule
import chisel3.experimental.hierarchy.instantiable

class BranchResultBundle(parameter: OGPUParameter) extends Bundle {
  val pc = UInt(parameter.xLen.W)
  val wid = UInt(log2Ceil(parameter.warpNum).W)
  val target = UInt(parameter.xLen.W)
}

class ScalarBranchInterface(parameter: OGPUParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val branchInfo = Flipped(DecoupledIO(new BranchInfoBundle(parameter)))
  val branchResult = Valid(new BranchResultBundle(parameter))
}

@instantiable
class ScalarBranch(val parameter: OGPUParameter)
    extends FixedIORawModule(new ScalarBranchInterface(parameter))
    with SerializableModule[OGPUParameter]
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

    val op1 = Mux(isJal, io.branchInfo.bits.pc, Mux(isJalr, io.branchInfo.bits.rs1Data, io.branchInfo.bits.pc))
    val op2 = Mux(isJal || isJalr, io.branchInfo.bits.imm, Mux(io.branchInfo.bits.isRVC, 2.U, 4.U))
    val target = op1 + op2

    io.branchResult.valid := true.B
    io.branchInfo.ready := true.B
    io.branchResult.bits.wid := io.branchInfo.bits.warpID
    io.branchResult.bits.pc := io.branchInfo.bits.pc
    io.branchResult.bits.target := target
  }
}
