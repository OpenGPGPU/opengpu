package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.SerializableModule

// Issue stage interface bundle
class IssueStageInterface(parameter: OGPUDecoderParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())

  // Input interface
  val in = Flipped(DecoupledIO(new Bundle {
    val instruction = new InstructionBundle(parameter.warpNum, 32)
    val coreResult = new CoreDecoderInterface(parameter)
    val fpuResult = new FPUDecoderInterface(parameter)
    val rvc = Bool()
  }))

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

  val fpuIssue = DecoupledIO(new Bundle {
    val rs1Data = UInt(parameter.xLen.W)
    val rs2Data = UInt(parameter.xLen.W)
    val rs3Data = UInt(parameter.xLen.W)
    val rnd_mode = UInt(3.W)
    val op = UInt(5.W)
    val op_mod = Bool()
    val src_fmt = UInt(2.W)
    val dst_fmt = UInt(2.W)
    val int_fmt = UInt(2.W)
    val vectorial_op = Bool()
    val tag_i = UInt(5.W)
    val flush = Bool()
    val warpID = UInt(log2Ceil(parameter.warpNum).W)
    val rd = UInt(5.W)
    val pc = UInt(parameter.xLen.W)
    val isRVC = Bool()
  })

  // Register file interfaces
  val intRegFile = Flipped(new RegFileReadIO(parameter.xLen, opNum = 2))
  val fpRegFile = Flipped(new RegFileReadIO(parameter.xLen, opNum = 3))
  val vecRegFile = Flipped(new RegFileReadIO(parameter.vLen, opNum = 2))

  // Scoreboard interfaces
  val intScoreboard = Flipped(
    new WarpScoreboardReadIO(
      WarpScoreboardParameter(parameter.warpNum, 32, zero = true, opNum = 2)
    )
  )
  val fpScoreboard = Flipped(
    new WarpScoreboardReadIO(
      WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 3)
    )
  )
  val vecScoreboard = Flipped(
    new WarpScoreboardReadIO(
      WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 2)
    )
  )
}

class IssueStage(val parameter: OGPUDecoderParameter)
    extends FixedIORawModule(new IssueStageInterface(parameter))
    with SerializableModule[OGPUDecoderParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // Extract instruction fields
  val inst = io.in.bits.instruction.instruction
  val rs1 = inst(19, 15)
  val rs2 = inst(24, 20)
  val rs3 = inst(31, 27)
  val rd = inst(11, 7)
  val imm = WireDefault(0.U(parameter.xLen.W))

  // Immediate decoding based on instruction format
  val immI = Cat(Fill(20, inst(31)), inst(31, 20))
  val immS = Cat(Fill(20, inst(31)), inst(31, 25), inst(11, 7))
  val immB = Cat(Fill(20, inst(31)), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W))
  val immU = Cat(inst(31, 12), Fill(12, 0.U))
  val immJ = Cat(Fill(12, inst(31)), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))

  // Select immediate based on instruction type
  val decode = io.in.bits.coreResult.output
  val fpuDecode = io.in.bits.fpuResult.output
  when(decode(parameter.selImm) === 0.U) { imm := immI }
    .elsewhen(decode(parameter.selImm) === 1.U) { imm := immS }
    .elsewhen(decode(parameter.selImm) === 2.U) { imm := immB }
    .elsewhen(decode(parameter.selImm) === 3.U) { imm := immU }
    .elsewhen(decode(parameter.selImm) === 4.U) { imm := immJ }

  // Suggestion: Use execType field from Decoder output
  val execType = decode(parameter.execType)

  val isALU = execType === parameter.ExecutionType.ALU
  val isFPU = execType === parameter.ExecutionType.FPU
  val isVectorInst = execType === parameter.ExecutionType.VEC

  // Other control signals
  val useRs1 = decode(parameter.selAlu1) === 1.U
  val useRs2 = decode(parameter.selAlu2) === 2.U
  val useRs3 = isFPU && fpuDecode(parameter.fren3)

  // Scoreboard read ports
  io.intScoreboard.warpID := io.in.bits.instruction.wid
  io.intScoreboard.addr(0) := rs1
  io.intScoreboard.addr(1) := rs2
  io.fpScoreboard.warpID := io.in.bits.instruction.wid
  io.fpScoreboard.addr(0) := rs1
  io.fpScoreboard.addr(1) := rs2
  io.fpScoreboard.addr(2) := rs3
  io.vecScoreboard.warpID := io.in.bits.instruction.wid
  io.vecScoreboard.addr(0) := rs1
  io.vecScoreboard.addr(1) := rs2

  // Connect integer register read ports
  io.intRegFile.read(0).addr := rs1
  io.intRegFile.read(1).addr := rs2

  // Connect floating-point register read ports
  io.fpRegFile.read(0).addr := rs1
  io.fpRegFile.read(1).addr := rs2
  io.fpRegFile.read(2).addr := rs3

  // Connect vector register read ports
  io.vecRegFile.read(0).addr := rs1
  io.vecRegFile.read(1).addr := rs2

  // Extract data for issue
  val rs1Data = Mux1H(
    Seq(
      isALU -> io.intRegFile.readData(0),
      isFPU -> io.fpRegFile.readData(0),
      isVectorInst -> io.vecRegFile.readData(0)
    )
  )
  val rs2Data = Mux1H(
    Seq(
      isALU -> io.intRegFile.readData(1),
      isFPU -> io.fpRegFile.readData(1),
      isVectorInst -> io.vecRegFile.readData(1)
    )
  )
  val rs3Data = io.fpRegFile.readData(2)

  // Scoreboard busy checks
  val rs1Busy =
    (isALU && io.intScoreboard.busy.lift(0).getOrElse(false.B)) ||
      (isFPU && io.fpScoreboard.busy.lift(0).getOrElse(false.B)) ||
      (isVectorInst && io.vecScoreboard.busy.lift(0).getOrElse(false.B))

  val rs2Busy =
    (isALU && io.intScoreboard.busy.lift(1).getOrElse(false.B)) ||
      (isFPU && io.fpScoreboard.busy.lift(1).getOrElse(false.B)) ||
      (isVectorInst && io.vecScoreboard.busy.lift(1).getOrElse(false.B)) && useRs2

  val rs3Busy = isFPU && io.fpScoreboard.busy.lift(2).getOrElse(false.B) && useRs3

  val anyRegBusy = rs1Busy || rs2Busy || rs3Busy

  // Issue conditions
  val canIssue = !anyRegBusy

  // Issue to different units
  io.aluIssue.valid := isALU && canIssue
  io.aluIssue.bits.warpID := io.in.bits.instruction.wid
  io.aluIssue.bits.execType := execType
  io.aluIssue.bits.aluFn := decode(parameter.aluFn)
  io.aluIssue.bits.funct3 := inst(14, 12)
  io.aluIssue.bits.funct7 := inst(31, 25)
  io.aluIssue.bits.pc := io.in.bits.instruction.pc
  io.aluIssue.bits.rs1Data := io.intRegFile.readData(0)
  io.aluIssue.bits.rs2Data := io.intRegFile.readData(1)
  io.aluIssue.bits.rd := rd
  io.aluIssue.bits.isRVC := io.in.bits.rvc

  io.fpuIssue.valid := isFPU && canIssue
  io.fpuIssue.bits.rs1Data := io.fpRegFile.readData(0)
  io.fpuIssue.bits.rs2Data := io.fpRegFile.readData(1)
  io.fpuIssue.bits.rs3Data := io.fpRegFile.readData(2)
  // TODO
  io.fpuIssue.bits.rnd_mode := 0.U
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
  io.fpuIssue.bits.isRVC := io.in.bits.rvc

  // Assign in.ready signal
  // 根据发射类型设置in.ready
  io.in.ready := Mux(
    isALU,
    io.aluIssue.ready,
    Mux(
      isFPU,
      io.fpuIssue.ready,
      true.B // 默认值，可根据需要修改
    )
  )
}
