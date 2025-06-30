package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.SerializableModule

// Bundle for register file read ports
class RegFileReadPort(xLen: Int) extends Bundle {
  val addr = Output(UInt(5.W))
  val data = Input(UInt(xLen.W))
}

// Define an ExecutionType enumeration
object ExecutionType {
  val ALU = 0.U(2.W)
  val FPU = 1.U(2.W)
  val VEC = 2.U(2.W)
}

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

  val fpuIssue = Output(new Bundle {
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
    val valid = Bool()
    val warpID = UInt(log2Ceil(parameter.warpNum).W)
    val rd = UInt(5.W)
    val pc = UInt(parameter.xLen.W)
    val isRVC = Bool()
  })

  // Register file interfaces
  val intRegFile = new Bundle {
    val rs1 = new RegFileReadPort(parameter.xLen)
    val rs2 = new RegFileReadPort(parameter.xLen)
  }
  val fpRegFile = new Bundle {
    val rs1 = new RegFileReadPort(parameter.xLen)
    val rs2 = new RegFileReadPort(parameter.xLen)
    val rs3 = new RegFileReadPort(parameter.xLen)
  }
  val vecRegFile = new Bundle {
    val rs1 = new RegFileReadPort(parameter.vLen)
    val rs2 = new RegFileReadPort(parameter.vLen)
  }

  // Scoreboard interfaces
  val intScoreboard = new WarpScoreboardInterface(
    WarpScoreboardParameter(parameter.warpNum, 32, zero = true, opNum = 2)
  )
  val fpScoreboard = new WarpScoreboardInterface(
    WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 3)
  )
  val vecScoreboard = new WarpScoreboardInterface(
    WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 2)
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

  val useRs1 = decode(parameter.selAlu1) === 1.U
  val useRs2 = decode(parameter.selAlu2) === 2.U
  val isFloatInst = fpuDecode(parameter.fwen) || fpuDecode(parameter.fren1)
  val isVectorInst = decode(parameter.vector)
  val isIntInst = !isFloatInst && !isVectorInst
  val useRs3 = isFloatInst

  // Select appropriate scoreboard
  val scoreboardRead = Mux1H(
    Seq(
      isIntInst -> io.intScoreboard.read,
      isFloatInst -> io.fpScoreboard.read,
      isVectorInst -> io.vecScoreboard.read
    )
  )
  val scoreboardBusy = Mux1H(
    Seq(
      isIntInst -> io.intScoreboard.busy,
      isFloatInst -> io.fpScoreboard.busy,
      isVectorInst -> io.vecScoreboard.busy
    )
  )

  // Connect scoreboard read ports
  scoreboardRead(0).addr := rs1
  scoreboardRead(1).addr := rs2
  if (parameter.xLen >= 3) scoreboardRead(2).addr := rs3

  // Check busy status
  val rs1Busy = scoreboardRead(0).busy && useRs1
  val rs2Busy = scoreboardRead(1).busy && useRs2
  val rs3Busy = isFloatInst && scoreboardRead.lift(2).map(_.busy).getOrElse(false.B) && useRs3
  val anyRegBusy = rs1Busy || rs2Busy || rs3Busy

  val canIssue = !anyRegBusy && !scoreboardBusy

  // Connect ScoreboardInterface clock/reset
  io.intScoreboard.clock := io.clock
  io.intScoreboard.reset := io.reset
  io.fpScoreboard.clock := io.clock
  io.fpScoreboard.reset := io.reset
  io.vecScoreboard.clock := io.clock
  io.vecScoreboard.reset := io.reset

  // Connect set/clear signals
  io.intScoreboard.set.en := isIntInst && decode(parameter.wxd)
  io.intScoreboard.set.warpID := io.in.bits.instruction.wid
  io.intScoreboard.set.addr := rd
  io.intScoreboard.clear.en := false.B
  io.intScoreboard.clear.warpID := 0.U
  io.intScoreboard.clear.addr := 0.U

  io.fpScoreboard.set.en := isFloatInst && fpuDecode(parameter.fwen)
  io.fpScoreboard.set.warpID := io.in.bits.instruction.wid
  io.fpScoreboard.set.addr := rd
  io.fpScoreboard.clear.en := false.B
  io.fpScoreboard.clear.warpID := 0.U
  io.fpScoreboard.clear.addr := 0.U

  io.vecScoreboard.set.en := isVectorInst && decode(parameter.vector)
  io.vecScoreboard.set.warpID := io.in.bits.instruction.wid
  io.vecScoreboard.set.addr := rd
  io.vecScoreboard.clear.en := false.B
  io.vecScoreboard.clear.warpID := 0.U
  io.vecScoreboard.clear.addr := 0.U
}
