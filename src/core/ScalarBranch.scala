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
    val pc_imm = io.branchInfo.bits.pc + io.branchInfo.bits.imm
    val pc_rs1 = io.branchInfo.bits.rs1Data + io.branchInfo.bits.imm
    val pc_next = io.branchInfo.bits.pc + 4.U

    val isJal = io.branchInfo.bits.branch.isJal
    val isJalr = io.branchInfo.bits.branch.isJalr
    val isBranch = io.branchInfo.bits.branch.isBranch

    io.branchResult.valid := true.B
    io.branchInfo.ready := io.branchResult.ready

    when(isJal) { // jal
      io.branchResult.bits.pc := pc_imm
      io.branchResult.bits.wid := io.branchInfo.bits.warpID
    }.elsewhen(isJalr) { // jalr
      io.branchResult.bits.pc := pc_rs1
      io.branchResult.bits.wid := io.branchInfo.bits.warpID
    }.elsewhen(isBranch) { // branch
      io.branchResult.bits.pc := pc_imm // 或根据分支条件选择pc_next/pc_imm
      io.branchResult.bits.wid := io.branchInfo.bits.warpID
    }
  }
}
