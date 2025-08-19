package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.SerializableModule
import ogpu.core.{ALUOperandBundle, FPUOperandBundle}

// Issue stage interface bundle
class IssueStageInterface(parameter: OGPUParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())

  // Input interface
  val in = Flipped(DecoupledIO(new Bundle {
    val instruction = new InstructionBundle(parameter.warpNum, 32)
    val coreResult = new CoreDecoderInterface(parameter)
    val fpuResult = new FPUDecoderInterface(parameter)
    val rvc = Bool()
  }))

  val aluIssue = DecoupledIO(new ALUOperandBundle(parameter))

  val fpuIssue = DecoupledIO(new FPUOperandBundle(parameter))

  // Register file interfaces
  val intRegFile = Flipped(new RegFileReadBundle(parameter.xLen, opNum = 3)) // ALU使用0-1，FPU使用2
  val fpRegFile = Flipped(new RegFileReadBundle(parameter.xLen, opNum = 3))
  val vecRegFile = Flipped(new RegFileReadBundle(parameter.vLen, opNum = 2))

  // Scoreboard interfaces
  val intScoreboard = Flipped(
    new WarpScoreboardReadBundle(
      WarpScoreboardParameter(parameter.warpNum, 32, zero = true, opNum = 5)
    )
  )
  val fpScoreboard = Flipped(
    new WarpScoreboardReadBundle(
      WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 4)
    )
  )
  val vecScoreboard = Flipped(
    new WarpScoreboardReadBundle(
      WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 3)
    )
  )

  // Scoreboard set interfaces
  val intScoreboardSet = Output(
    new ScoreboardSetBundle(WarpScoreboardParameter(parameter.warpNum, 32, zero = true, opNum = 1))
  )
  val fpScoreboardSet = Output(
    new ScoreboardSetBundle(WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 4))
  )
  val vecScoreboardSet = Output(
    new ScoreboardSetBundle(WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 3))
  )
  // Memory issue to LSU
  val memIssue = DecoupledIO(new LSUReq(parameter))
}

class IssueStage(val parameter: OGPUParameter)
    extends FixedIORawModule(new IssueStageInterface(parameter))
    with SerializableModule[OGPUParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // 子模块实例化
  val aluIssueModule = withClockAndReset(io.clock, io.reset) { Module(new AluIssue(parameter)) }
  val fpuIssueModule = withClockAndReset(io.clock, io.reset) { Module(new FpuIssue(parameter)) }

  // ALU 子模块信号连接
  aluIssueModule.io.in.valid := io.in.valid && (io.in.bits.coreResult
    .output(parameter.execType) === parameter.ExecutionType.ALU)
  aluIssueModule.io.in.bits.instruction := io.in.bits.instruction
  aluIssueModule.io.in.bits.coreResult := io.in.bits.coreResult
  aluIssueModule.io.in.bits.rvc := io.in.bits.rvc
  // ALU使用intRegFile的端口0-1
  io.intRegFile.read(0).addr := aluIssueModule.io.intRegFile.read(0).addr
  aluIssueModule.io.intRegFile.readData(0) := io.intRegFile.readData(0)
  io.intRegFile.read(1).addr := aluIssueModule.io.intRegFile.read(1).addr
  aluIssueModule.io.intRegFile.readData(1) := io.intRegFile.readData(1)
  // ALU使用intScoreboard的端口0-2
  io.intScoreboard.warpID := aluIssueModule.io.intScoreboard.warpID
  io.intScoreboard.addr(0) := aluIssueModule.io.intScoreboard.addr(0)
  io.intScoreboard.addr(1) := aluIssueModule.io.intScoreboard.addr(1)
  io.intScoreboard.addr(2) := aluIssueModule.io.intScoreboard.addr(2)
  aluIssueModule.io.intScoreboard.busy(0) := io.intScoreboard.busy(0)
  aluIssueModule.io.intScoreboard.busy(1) := io.intScoreboard.busy(1)
  aluIssueModule.io.intScoreboard.busy(2) := io.intScoreboard.busy(2)
  io.aluIssue <> aluIssueModule.io.aluIssue

  // FPU 子模块信号连接
  fpuIssueModule.io.in.valid := io.in.valid && (io.in.bits.coreResult
    .output(parameter.execType) === parameter.ExecutionType.FPU)
  fpuIssueModule.io.in.bits.instruction := io.in.bits.instruction
  fpuIssueModule.io.in.bits.fpuResult := io.in.bits.fpuResult
  fpuIssueModule.io.fpRegFile <> io.fpRegFile
  // FPU使用intRegFile的端口2
  io.intRegFile.read(2).addr := fpuIssueModule.io.intRegFile.read(0).addr
  fpuIssueModule.io.intRegFile.readData(0) := io.intRegFile.readData(2)
  fpuIssueModule.io.fpScoreboard <> io.fpScoreboard
  // FPU使用intScoreboard的端口3-4
  io.intScoreboard.warpID := fpuIssueModule.io.intScoreboard.warpID
  io.intScoreboard.addr(3) := fpuIssueModule.io.intScoreboard.addr(0)
  io.intScoreboard.addr(4) := fpuIssueModule.io.intScoreboard.addr(1)
  fpuIssueModule.io.intScoreboard.busy(0) := io.intScoreboard.busy(3)
  fpuIssueModule.io.intScoreboard.busy(1) := io.intScoreboard.busy(4)
  io.fpuIssue <> fpuIssueModule.io.fpuIssue

  // ===== 内存指令发射（最小实现） =====
  val isMem = io.in.valid && (io.in.bits.coreResult.output(parameter.execType) === parameter.ExecutionType.MEM)
  io.memIssue.valid := isMem
  io.memIssue.bits.wid := io.in.bits.instruction.wid
  val memInst = io.in.bits.instruction.instruction
  val memImmI = Cat(Fill(20, memInst(31)), memInst(31, 20))
  val memRs1Data = io.intRegFile.readData(0)
  io.memIssue.bits.vaddr := (memRs1Data.asUInt + memImmI.asUInt)(parameter.vaddrBitsExtended - 1, 0)
  val memFunct3 = memInst(14, 12)
  val memIsStore = memInst(5)
  io.memIssue.bits.cmd := Mux(memIsStore, 1.U, 0.U)
  io.memIssue.bits.size := memFunct3(1, 0) ## 0.U(1.W)
  io.memIssue.bits.data := io.intRegFile.readData(1) // rs2 for store
  // rd for load writeback
  io.memIssue.bits.rd := memInst(11, 7)

  // 其他接口直通
  io.vecRegFile <> DontCare
  io.vecScoreboard <> DontCare

  // in.ready 由子模块 ready 控制
  io.in.ready := Mux(
    io.in.bits.coreResult.output(parameter.execType) === parameter.ExecutionType.ALU,
    aluIssueModule.io.in.ready,
    Mux(
      io.in.bits.coreResult.output(parameter.execType) === parameter.ExecutionType.FPU,
      fpuIssueModule.io.in.ready,
      io.memIssue.ready // MEM 指令由 memIssue 控制
    )
  )

  // Scoreboard set logic - only set when instruction is actually issued (fire)
  // ALU scoreboard set (使用端口0-2)
  io.intScoreboardSet.en := io.aluIssue.fire && (io.aluIssue.bits.rd =/= 0.U)
  io.intScoreboardSet.warpID := io.aluIssue.bits.warpID
  io.intScoreboardSet.addr := io.aluIssue.bits.rd

  // FPU scoreboard set
  io.fpScoreboardSet.en := io.fpuIssue.fire
  io.fpScoreboardSet.warpID := io.fpuIssue.bits.warpID
  io.fpScoreboardSet.addr := io.fpuIssue.bits.rd

  // Vector scoreboard set (placeholder for future use)
  io.vecScoreboardSet.en := false.B
  io.vecScoreboardSet.warpID := 0.U
  io.vecScoreboardSet.addr := 0.U
}
