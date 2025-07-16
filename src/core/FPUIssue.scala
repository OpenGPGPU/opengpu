package ogpu.core

import chisel3._
import chisel3.util._

class FpuIssueIO(parameter: OGPUDecoderParameter) extends Bundle {
  val in = Flipped(DecoupledIO(new Bundle {
    val instruction = new InstructionBundle(parameter.warpNum, 32)
    val fpuResult = new FPUDecoderInterface(parameter)
  }))
  val fpRegFile = Flipped(new RegFileReadIO(parameter.xLen, opNum = 3))
  val fpScoreboard = Flipped(
    new WarpScoreboardReadIO(
      WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 4)
    )
  )
  val fpuIssue = DecoupledIO(new FPUOperandBundle(parameter))
}

class FpuIssue(val parameter: OGPUDecoderParameter) extends Module {
  val io = IO(new FpuIssueIO(parameter))

  val inst = io.in.bits.instruction.instruction
  val rs1 = inst(19, 15)
  val rs2 = inst(24, 20)
  val rs3 = inst(31, 27)
  val rd = inst(11, 7)
  val fpuDecode = io.in.bits.fpuResult.output

  // 控制信号：是否使用寄存器
  val useRs1 = true.B // FPU 指令通常都使用 rs1
  val useRs2 = true.B // FPU 指令通常都使用 rs2
  val useRs3 = fpuDecode(parameter.fren3)
  val useRd = fpuDecode(parameter.fwen)

  // 寄存器读
  io.fpRegFile.read(0).addr := rs1
  io.fpRegFile.read(1).addr := rs2
  io.fpRegFile.read(2).addr := rs3

  // Scoreboard 读
  io.fpScoreboard.warpID := io.in.bits.instruction.wid
  io.fpScoreboard.addr(0) := rs1
  io.fpScoreboard.addr(1) := rs2
  io.fpScoreboard.addr(2) := rs3
  io.fpScoreboard.addr(3) := rd

  // busy 检查：只有当真正使用寄存器时才检查 busy
  val rs1Busy = useRs1 && io.fpScoreboard.busy.lift(0).getOrElse(false.B)
  val rs2Busy = useRs2 && io.fpScoreboard.busy.lift(1).getOrElse(false.B)
  val rs3Busy = useRs3 && io.fpScoreboard.busy.lift(2).getOrElse(false.B)
  val rdBusy = useRd && io.fpScoreboard.busy.lift(3).getOrElse(false.B) && (rd =/= 0.U)
  val anyRegBusy = rs1Busy || rs2Busy || rs3Busy
  val canIssue = !anyRegBusy && !rdBusy

  // FPU 发射
  io.fpuIssue.valid := canIssue
  io.fpuIssue.bits.rs1Data := io.fpRegFile.readData(0)
  io.fpuIssue.bits.rs2Data := io.fpRegFile.readData(1)
  io.fpuIssue.bits.rs3Data := io.fpRegFile.readData(2)
  io.fpuIssue.bits.rnd_mode := 0.U // TODO: 需要根据实际情况赋值
  io.fpuIssue.bits.op := fpuDecode(parameter.op)
  io.fpuIssue.bits.op_mod := fpuDecode(parameter.op_mod)
  io.fpuIssue.bits.src_fmt := fpuDecode(parameter.src_fmt)
  io.fpuIssue.bits.dst_fmt := fpuDecode(parameter.dst_fmt)
  io.fpuIssue.bits.int_fmt := fpuDecode(parameter.int_fmt)
  io.fpuIssue.bits.vectorial_op := fpuDecode(parameter.vectorial_op)
  io.fpuIssue.bits.tag_i := fpuDecode(parameter.tag_i)
  io.fpuIssue.bits.flush := false.B
  io.fpuIssue.bits.warpID := io.in.bits.instruction.wid
  io.fpuIssue.bits.rd := rd
  io.fpuIssue.bits.pc := io.in.bits.instruction.pc

  // ready 信号
  io.in.ready := io.fpuIssue.ready
}
