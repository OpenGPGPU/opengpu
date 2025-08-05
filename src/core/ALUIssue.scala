package ogpu.core

import chisel3._
import chisel3.util._

class AluIssueIO(parameter: OGPUParameter) extends Bundle {
  val in = Flipped(DecoupledIO(new Bundle {
    val instruction = new InstructionBundle(parameter.warpNum, 32)
    val coreResult = new CoreDecoderInterface(parameter)
    val rvc = Bool()
  }))
  val intRegFile = Flipped(new RegFileReadBundle(parameter.xLen, opNum = 2))
  val intScoreboard = Flipped(
    new WarpScoreboardReadBundle(
      WarpScoreboardParameter(parameter.warpNum, 32, zero = true, opNum = 3)
    )
  )
  val aluIssue = DecoupledIO(new ALUOperandBundle(parameter))
}

class AluIssue(val parameter: OGPUParameter) extends Module {
  val io = IO(new AluIssueIO(parameter))

  val inst = io.in.bits.instruction.instruction
  val rs1 = inst(19, 15)
  val rs2 = inst(24, 20)
  val rd = inst(11, 7)
  val imm = WireDefault(0.U(parameter.xLen.W))
  val rs1Data = WireDefault(0.U(parameter.xLen.W))
  val rs2Data = WireDefault(0.U(parameter.xLen.W))

  // Immediate decoding based on instruction format
  val immI = Cat(Fill(20, inst(31)), inst(31, 20))
  val immS = Cat(Fill(20, inst(31)), inst(31, 25), inst(11, 7))
  val immB = Cat(Fill(20, inst(31)), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W))
  val immU = Cat(inst(31, 12), Fill(12, 0.U))
  val immJ = Cat(Fill(12, inst(31)), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))

  // Select immediate based on instruction type
  val decode = io.in.bits.coreResult.output
  when(decode(parameter.selImm) === 0.U) { imm := immI }
    .elsewhen(decode(parameter.selImm) === 1.U) { imm := immS }
    .elsewhen(decode(parameter.selImm) === 2.U) { imm := immB }
    .elsewhen(decode(parameter.selImm) === 3.U) { imm := immU }
    .elsewhen(decode(parameter.selImm) === 4.U) { imm := immJ }

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

  val selAlu1 = decode(parameter.selAlu1)
  val selAlu2 = decode(parameter.selAlu2)

  // 选择 rs1Data 来源
  val rs1DataSel = Wire(UInt(parameter.xLen.W))
  rs1DataSel := 0.U
  when(selAlu1 === 0.U) { // zero
    rs1DataSel := 0.U
  }.elsewhen(selAlu1 === 1.U) { // rs1
    rs1DataSel := io.intRegFile.readData(0)
  }.elsewhen(selAlu1 === 2.U) { // pc
    rs1DataSel := io.in.bits.instruction.pc
  }

  // 选择 rs2Data 来源
  val rs2DataSel = Wire(UInt(parameter.xLen.W))
  rs2DataSel := 0.U
  when(selAlu2 === 0.U) { // zero
    rs2DataSel := 0.U
  }.elsewhen(selAlu2 === 1.U) { // size (如 jal 指令)
    rs2DataSel := 4.U // 目前假定 size=4
  }.elsewhen(selAlu2 === 2.U) { // rs2
    rs2DataSel := io.intRegFile.readData(1)
  }.elsewhen(selAlu2 === 3.U) { // imm
    rs2DataSel := imm
  }

  // ALU 发射
  io.aluIssue.valid := canIssue && io.in.valid
  io.aluIssue.bits.warpID := io.in.bits.instruction.wid
  io.aluIssue.bits.execType := decode(parameter.execType)
  io.aluIssue.bits.aluFn := decode(parameter.aluFn)
  io.aluIssue.bits.funct3 := inst(14, 12)
  io.aluIssue.bits.funct7 := inst(31, 25)
  io.aluIssue.bits.pc := io.in.bits.instruction.pc
  io.aluIssue.bits.rs1Data := rs1DataSel
  io.aluIssue.bits.rs2Data := rs2DataSel
  io.aluIssue.bits.imm := imm
  io.aluIssue.bits.rd := rd
  io.aluIssue.bits.isRVC := io.in.bits.rvc

  // Branch control signals
  io.aluIssue.bits.branch.isJal := decode(parameter.isJal)
  io.aluIssue.bits.branch.isJalr := decode(parameter.isJalr)
  io.aluIssue.bits.branch.isBranch := decode(parameter.isBranch)

  // ready 信号
  io.in.ready := io.aluIssue.ready
}
