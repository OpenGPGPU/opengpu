package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.SerializableModule

/** ComputeUnit接口
  *
  * 使用更清晰的模块化设计
  */
class ComputeUnitInterface(parameter: OGPUParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())

  // 任务接口
  val task = Flipped(
    DecoupledIO(
      new CuTaskBundle(
        threadNum = 32,
        warpNum = parameter.warpNum,
        dimNum = 4,
        xLen = parameter.xLen
      )
    )
  )

  // 内存接口
  val memory = new Bundle {
    val tilelink = new Bundle {
      val a = DecoupledIO(new Bundle {
        val address = UInt(34.W)
        val source = UInt(8.W)
        val sink = UInt(8.W)
        val data = UInt(64.W)
        val size = UInt(4.W)
      })
      val d = Flipped(DecoupledIO(new Bundle {
        val address = UInt(34.W)
        val source = UInt(8.W)
        val sink = UInt(8.W)
        val data = UInt(64.W)
        val size = UInt(4.W)
      }))
    }
  }

  // 状态和控制信号
  val busy = Output(Bool())
  val idle = Output(Bool())
  val exception = Output(Bool())

  // 调试和监控
  val debug = Output(new Bundle {
    val frontendBusy = Bool()
    val executionBusy = Bool()
    val dataManagerBusy = Bool()
    val activeWarps = UInt(parameter.warpNum.W)
  })
}

/** ComputeUnit
  *
  * 使用模块化设计，将复杂的逻辑分解为更小的、可管理的模块
  */
@instantiable
class ComputeUnit(val parameter: OGPUParameter)
    extends FixedIORawModule(new ComputeUnitInterface(parameter))
    with SerializableModule[OGPUParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // ===== 主要模块实例化 =====

  // 前端流水线
  val frontendPipeline = Module(new FrontendPipeline(parameter))

  // 执行流水线
  val executionPipeline = Module(new ExecutionPipeline(parameter))

  // 数据管理模块
  val dataManager = Module(new DataManager(parameter))

  // 分支解析模块
  val scalarBranch = Module(new ScalarBranch(parameter))

  // ===== 时钟和复位连接 =====

  frontendPipeline.io.clock := io.clock
  frontendPipeline.io.reset := io.reset
  executionPipeline.io.clock := io.clock
  executionPipeline.io.reset := io.reset
  dataManager.io.clock := io.clock
  dataManager.io.reset := io.reset
  scalarBranch.io.clock := io.clock
  scalarBranch.io.reset := io.reset

  // ===== 任务接口连接 =====

  // 连接任务到前端流水线
  frontendPipeline.io.warp.start <> io.task

  // ===== 内存接口连接 =====

  // 连接内存接口到前端流水线
  frontendPipeline.io.memory <> io.memory

  // ===== 指令流水线连接 =====

  // 连接前端到执行流水线
  executionPipeline.io.instruction <> frontendPipeline.io.instruction

  // ===== 数据管理连接 =====

  // 连接执行流水线到数据管理
  executionPipeline.io.regFile <> dataManager.io.regFile
  executionPipeline.io.scoreboard <> dataManager.io.scoreboard

  // ===== 分支处理连接 =====

  // 连接执行流水线到分支解析
  scalarBranch.io.branchInfo <> executionPipeline.io.branchInfo

  // 连接分支解析到前端流水线
  frontendPipeline.io.branchUpdate <> scalarBranch.io.branchResult

  // ===== 流水线控制 =====

  // 全局流水线控制信号
  val globalFlush = Wire(Bool())
  val globalStall = Wire(Bool())

  // 连接控制信号
  frontendPipeline.io.flush := globalFlush
  frontendPipeline.io.stall := globalStall
  executionPipeline.io.flush := globalFlush
  executionPipeline.io.stall := globalStall

  // 流水线控制逻辑
  globalFlush := false.B // 可以基于异常或其他条件设置
  globalStall := false.B // 可以基于数据相关性或其他条件设置

  // ===== 状态信号生成 =====

  // 忙碌状态
  val frontendBusy = frontendPipeline.io.instruction.valid
  val executionBusy = executionPipeline.io.result.valid
  val dataManagerBusy = dataManager.io.status.intRegBusy.orR ||
    dataManager.io.status.fpRegBusy.orR ||
    dataManager.io.status.vecRegBusy.orR

  io.busy := frontendBusy || executionBusy || dataManagerBusy
  io.idle := !io.busy

  // 异常状态
  io.exception := executionPipeline.io.result.bits.exception

  // ===== 调试信号 =====

  io.debug.frontendBusy := frontendBusy
  io.debug.executionBusy := executionBusy
  io.debug.dataManagerBusy := dataManagerBusy
  io.debug.activeWarps := 0.U // 可以基于实际的warp状态设置

  // ===== 性能监控 =====

  // 性能计数器
  val cycleCounter = RegInit(0.U(64.W))
  val instructionCounter = RegInit(0.U(64.W))
  val warpCounter = RegInit(0.U(32.W))

  cycleCounter := cycleCounter + 1.U
  when(executionPipeline.io.result.fire) {
    instructionCounter := instructionCounter + 1.U
  }
  when(io.task.fire) {
    warpCounter := warpCounter + 1.U
  }

  // ===== 断言和验证 =====

  // 确保流水线正确性
  assert(
    !(executionPipeline.io.result.valid && !executionPipeline.io.result.ready),
    "Execution pipeline result not ready"
  )

  // 确保数据相关性正确性
  assert(
    !(dataManager.io.status.intRegBusy.orR && executionPipeline.io.result.valid),
    "Register file busy during writeback"
  )

  // ===== 初始化 =====

  when(io.reset.asBool) {
    cycleCounter := 0.U
    instructionCounter := 0.U
    warpCounter := 0.U
  }
}
