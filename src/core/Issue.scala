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
      WarpScoreboardParameter(parameter.warpNum, 32, zero = true, opNum = 3)
    )
  )
  val fpScoreboard = Flipped(
    new WarpScoreboardReadIO(
      WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 4)
    )
  )
  val vecScoreboard = Flipped(
    new WarpScoreboardReadIO(
      WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 3)
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

  // 子模块实例化
  val aluIssueModule = withClockAndReset(io.clock, io.reset) { Module(new AluIssue(parameter)) }
  val fpuIssueModule = withClockAndReset(io.clock, io.reset) { Module(new FpuIssue(parameter)) }

  // ALU 子模块信号连接
  aluIssueModule.io.in.valid := io.in.valid && (io.in.bits.coreResult
    .output(parameter.execType) === parameter.ExecutionType.ALU)
  aluIssueModule.io.in.bits.instruction := io.in.bits.instruction
  aluIssueModule.io.in.bits.coreResult := io.in.bits.coreResult
  aluIssueModule.io.in.bits.rvc := io.in.bits.rvc
  aluIssueModule.io.intRegFile <> io.intRegFile
  aluIssueModule.io.intScoreboard <> io.intScoreboard
  io.aluIssue <> aluIssueModule.io.aluIssue

  // FPU 子模块信号连接
  fpuIssueModule.io.in.valid := io.in.valid && (io.in.bits.coreResult
    .output(parameter.execType) === parameter.ExecutionType.FPU)
  fpuIssueModule.io.in.bits.instruction := io.in.bits.instruction
  fpuIssueModule.io.in.bits.fpuResult := io.in.bits.fpuResult
  fpuIssueModule.io.in.bits.rvc := io.in.bits.rvc
  fpuIssueModule.io.fpRegFile <> io.fpRegFile
  fpuIssueModule.io.fpScoreboard <> io.fpScoreboard
  io.fpuIssue <> fpuIssueModule.io.fpuIssue

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
      true.B // 默认值
    )
  )
}
