package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.SerializableModule
import ogpu.fpu._

/** 执行流水线接口
  *
  * 封装了执行单元的所有接口
  */
class ExecutionPipelineInterface(parameter: OGPUParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())

  // 指令输入
  val instruction = Flipped(DecoupledIO(new Bundle {
    val instruction = new InstructionBundle(parameter.warpNum, 32)
    val coreResult = new CoreDecoderInterface(parameter)
    val fpuResult = new FPUDecoderInterface(parameter)
    val rvc = Bool()
  }))

  // 执行结果输出
  val result = DecoupledIO(new ResultBundle(parameter))

  // 分支信息输出
  val branchInfo = DecoupledIO(new BranchInfoBundle(parameter))

  // 寄存器文件接口
  val regFile = new Bundle {
    val intRead = Flipped(new RegFileReadIO(parameter.xLen, opNum = 3))
    val fpRead = Flipped(new RegFileReadIO(parameter.xLen, opNum = 3))
    val vecRead = Flipped(new RegFileReadIO(parameter.vLen, opNum = 2))
    val write = Output(new RegFileWriteBundle(parameter))
  }

  // Scoreboard接口
  val scoreboard = new Bundle {
    val intRead = Flipped(
      new WarpScoreboardReadIO(
        WarpScoreboardParameter(parameter.warpNum, 32, zero = true, opNum = 5)
      )
    )
    val fpRead = Flipped(
      new WarpScoreboardReadIO(
        WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 4)
      )
    )
    val vecRead = Flipped(
      new WarpScoreboardReadIO(
        WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 3)
      )
    )
    val intSet = Output(
      new ScoreboardSetBundle(
        WarpScoreboardParameter(parameter.warpNum, 32, zero = true, opNum = 1)
      )
    )
    val fpSet = Output(
      new ScoreboardSetBundle(
        WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 4)
      )
    )
    val vecSet = Output(
      new ScoreboardSetBundle(
        WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 3)
      )
    )
    val intClear = Output(
      new ScoreboardClearBundle(
        WarpScoreboardParameter(parameter.warpNum, 32, zero = true, opNum = 5)
      )
    )
    val fpClear = Output(
      new ScoreboardClearBundle(
        WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 4)
      )
    )
  }

  // 控制信号
  val flush = Input(Bool())
  val stall = Input(Bool())
}

/** 执行流水线
  *
  * 封装了Issue、Execute、Writeback三个阶段
  */
class ExecutionPipeline(val parameter: OGPUParameter)
    extends FixedIORawModule(new ExecutionPipelineInterface(parameter))
    with SerializableModule[OGPUParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // 子模块实例化
  val issueStage = Module(new IssueStage(parameter))
  val aluExecution = Module(new ALUExecution(parameter))
  val fpuExecution = Module(new FPUExecution(parameter))
  val writebackStage = Module(new WritebackStage(parameter))

  // 流水线控制器
  val pipelineController = Module(new PipelineController(parameter))

  // 连接时钟和复位
  issueStage.io.clock := io.clock
  issueStage.io.reset := io.reset
  aluExecution.io.clock := io.clock
  aluExecution.io.reset := io.reset
  fpuExecution.io.clock := io.clock
  fpuExecution.io.reset := io.reset

  // 连接指令输入
  issueStage.io.in <> io.instruction

  // 连接寄存器文件
  issueStage.io.intRegFile <> io.regFile.intRead
  issueStage.io.fpRegFile <> io.regFile.fpRead
  issueStage.io.vecRegFile <> io.regFile.vecRead

  // 连接Scoreboard
  issueStage.io.intScoreboard <> io.scoreboard.intRead
  issueStage.io.fpScoreboard <> io.scoreboard.fpRead
  issueStage.io.vecScoreboard <> io.scoreboard.vecRead
  io.scoreboard.intSet <> issueStage.io.intScoreboardSet
  io.scoreboard.fpSet <> issueStage.io.fpScoreboardSet
  io.scoreboard.vecSet <> issueStage.io.vecScoreboardSet

  // 连接执行单元
  aluExecution.io.in <> issueStage.io.aluIssue
  fpuExecution.io.in <> issueStage.io.fpuIssue

  // 连接写回阶段
  writebackStage.io.in.valid := aluExecution.io.out.valid || fpuExecution.io.out.valid
  writebackStage.io.in.bits := Mux(aluExecution.io.out.valid, aluExecution.io.out.bits, fpuExecution.io.out.bits)

  // 连接输出
  io.result.valid := writebackStage.io.in.valid
  io.result.bits := writebackStage.io.in.bits
  // writebackStage.io.in.ready 由 WritebackStage 内部驱动，不需要外部连接
  io.branchInfo <> aluExecution.io.branchInfo
  io.regFile.write <> writebackStage.io.regFileWrite
  io.scoreboard.intClear <> writebackStage.io.clearScoreboard
  io.scoreboard.fpClear <> writebackStage.io.clearScoreboard

  // 流水线控制
  pipelineController.io.globalFlush := io.flush
  pipelineController.io.globalStall := io.stall

  // 反馈ready信号
  pipelineController.io.stages(0).ready := issueStage.io.in.ready
  pipelineController.io.stages(1).ready := aluExecution.io.in.ready && fpuExecution.io.in.ready
  pipelineController.io.stages(2).ready := writebackStage.io.in.ready
}
