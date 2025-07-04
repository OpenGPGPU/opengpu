package ogpu.core

import chisel3._
import chisel3.util._

class AluIssueIO(parameter: OGPUDecoderParameter) extends Bundle {
  val in = Flipped(DecoupledIO(new Bundle {
    val instruction = new InstructionBundle(parameter.warpNum, 32)
    val coreResult = new CoreDecoderInterface(parameter)
    val rvc = Bool()
  }))
  val intRegFile = Flipped(new RegFileReadIO(parameter.xLen, opNum = 2))
  val intScoreboard = Flipped(
    new WarpScoreboardReadIO(
      WarpScoreboardParameter(parameter.warpNum, 32, zero = true, opNum = 3)
    )
  )
  val aluIssue = DecoupledIO(new Bundle {
    val warpID = UInt(log2Ceil(parameter.warpNum).W)
    val execType = UInt(2.W)
    val aluFn = UInt(parameter.UOPALU.width.W)
    val funct3 = UInt(3.W)
    val funct7 = UInt(7.W)
    val pc = UInt(parameter.xLen.W)
    val rs1Data = UInt(parameter.xLen.W)
    val rs2Data = UInt(parameter.xLen.W)
    val rd = UInt(5.W)
    val isRVC = Bool()
  })
}

class AluIssue(val parameter: OGPUDecoderParameter) extends Module {
  val io = IO(new AluIssueIO(parameter))

  val inst = io.in.bits.instruction.instruction
  val rs1 = inst(19, 15)
  val rs2 = inst(24, 20)
  val rd = inst(11, 7)
  val decode = io.in.bits.coreResult.output

  // 控制信号：是否使用寄存器
  val useRs1 = decode(parameter.selAlu1) === 1.U
  val useRs2 = decode(parameter.selAlu2) === 2.U
  val useRd = decode(parameter.wxd)

  // 寄存器读
  io.intRegFile.read(0).addr := rs1
  io.intRegFile.read(1).addr := rs2

  // Scoreboard 读
  io.intScoreboard.warpID := io.in.bits.instruction.wid
  io.intScoreboard.addr(0) := rs1
  io.intScoreboard.addr(1) := rs2
  io.intScoreboard.addr(2) := rd

  // busy 检查：只有当真正使用寄存器时才检查 busy
  val rs1Busy = useRs1 && io.intScoreboard.busy.lift(0).getOrElse(false.B)
  val rs2Busy = useRs2 && io.intScoreboard.busy.lift(1).getOrElse(false.B)
  val rdBusy = useRd && io.intScoreboard.busy.lift(2).getOrElse(false.B) && (rd =/= 0.U)
  val anyRegBusy = rs1Busy || rs2Busy
  val canIssue = !anyRegBusy && !rdBusy

  // ALU 发射
  io.aluIssue.valid := canIssue
  io.aluIssue.bits.warpID := io.in.bits.instruction.wid
  io.aluIssue.bits.execType := decode(parameter.execType)
  io.aluIssue.bits.aluFn := decode(parameter.aluFn)
  io.aluIssue.bits.funct3 := inst(14, 12)
  io.aluIssue.bits.funct7 := inst(31, 25)
  io.aluIssue.bits.pc := io.in.bits.instruction.pc
  io.aluIssue.bits.rs1Data := io.intRegFile.readData(0)
  io.aluIssue.bits.rs2Data := io.intRegFile.readData(1)
  io.aluIssue.bits.rd := rd
  io.aluIssue.bits.isRVC := io.in.bits.rvc

  // ready 信号
  io.in.ready := io.aluIssue.ready
}
