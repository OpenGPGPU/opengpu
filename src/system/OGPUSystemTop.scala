package ogpu.system

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.SerializableModule
import org.chipsalliance.tilelink.bundle._
import ogpu.dispatcher._

/** OGPU System Top Module
  *
  * 顶层系统模块，提供完整的OGPU系统接口 包含时钟域、复位管理、调试接口等
  */
@instantiable
class OGPUSystemTop(val parameter: OGPUSystemParameter)
    extends Module
    with SerializableModule[OGPUSystemParameter]
    with Public {

  val io = IO(new Bundle {
    // 时钟和复位
    val clock = Input(Clock())
    val reset = Input(Bool())

    // 队列接口（从主机）
    val queues = Vec(parameter.numQueues, Flipped(DecoupledIO(new QueueBundle)))
    val queue_resps = Vec(parameter.numQueues, DecoupledIO(new QueueRespBundle))

    // 内存接口（到系统内存）
    val memory = new TLLink(
      TLLinkParameter(
        addressWidth = 34,
        sourceWidth = 8,
        sinkWidth = 8,
        dataWidth = 64,
        sizeWidth = 4,
        hasBCEChannels = false
      )
    )

    // 调试和监控接口
    val debug = Output(new Bundle {
      val systemBusy = Bool()
      val activeComputeUnits = UInt(parameter.numComputeUnits.W)
      val activeWorkGroups = UInt(parameter.numWorkGroups.W)
      val queueUtilization = Vec(parameter.numQueues, Bool())
      val systemStatus = UInt(8.W)
      val performanceCounters = new Bundle {
        val totalCycles = UInt(64.W)
        val activeCycles = UInt(64.W)
        val completedTasks = UInt(32.W)
        val memoryTransactions = UInt(32.W)
      }
    })

    // 中断接口
    val interrupts = Output(new Bundle {
      val taskComplete = Bool()
      val systemError = Bool()
      val memoryError = Bool()
    })
  })

  // ===== 时钟域管理 =====

  // 主时钟域（使用Chisel的内置时钟域管理）
  // val clockDomain = ClockDomain(io.clock, io.reset) // ClockDomain不可用，使用withClockAndReset

  // 如果需要，可以创建其他时钟域
  // val memoryClockDomain = ClockDomain(io.memoryClock, io.memoryReset)

  // ===== 复位管理 =====

  // 复位同步器
  val resetSync = withClock(io.clock) {
    val resetReg = RegInit(true.B)
    resetReg := io.reset
    resetReg
  }

  // 软复位支持
  val softReset = withClock(io.clock) {
    val softResetReg = RegInit(false.B)
    val softResetReq = Wire(Bool())
    softResetReq := false.B // 可以通过调试接口触发

    when(softResetReq) {
      softResetReg := true.B
    }.elsewhen(!resetSync) {
      softResetReg := false.B
    }

    softResetReg
  }

  val systemReset = resetSync || softReset

  // ===== 核心系统实例化 =====

  val ogpuSystem = withClockAndReset(io.clock, systemReset) {
    Module(new OGPUSystem(parameter))
  }

  // ===== 接口连接 =====

  // 队列接口
  for (i <- 0 until parameter.numQueues) {
    ogpuSystem.io.queues(i) <> io.queues(i)
    ogpuSystem.io.queue_resps(i) <> io.queue_resps(i)
  }

  // 内存接口
  ogpuSystem.io.memory <> io.memory

  // 调试接口
  ogpuSystem.io.debug <> io.debug

  // ===== 性能计数器 =====

  val performanceCounters = withClock(io.clock) {
    val totalCycles = RegInit(0.U(64.W))
    val activeCycles = RegInit(0.U(64.W))
    val completedTasks = RegInit(0.U(32.W))
    val memoryTransactions = RegInit(0.U(32.W))

    // 总周期计数
    totalCycles := totalCycles + 1.U

    // 活跃周期计数
    when(io.debug.systemBusy) {
      activeCycles := activeCycles + 1.U
    }

    // 完成任务计数（通过队列响应检测）
    val taskCompleteSignal = io.queue_resps.map(qr => qr.valid && qr.ready).reduce(_ || _)
    when(taskCompleteSignal) {
      completedTasks := completedTasks + 1.U
    }

    // 内存事务计数
    val memoryTransaction = io.memory.a.valid && io.memory.a.ready
    when(memoryTransaction) {
      memoryTransactions := memoryTransactions + 1.U
    }

    // 复位时清零
    when(systemReset) {
      totalCycles := 0.U
      activeCycles := 0.U
      completedTasks := 0.U
      memoryTransactions := 0.U
    }

    val perfBundle = Wire(new Bundle {
      val totalCycles = UInt(64.W)
      val activeCycles = UInt(64.W)
      val completedTasks = UInt(32.W)
      val memoryTransactions = UInt(32.W)
    })
    perfBundle.totalCycles := totalCycles
    perfBundle.activeCycles := activeCycles
    perfBundle.completedTasks := completedTasks
    perfBundle.memoryTransactions := memoryTransactions
    perfBundle
  }

  io.debug.performanceCounters <> performanceCounters

  // ===== 系统状态 =====

  val systemStatus = withClock(io.clock) {
    val status = RegInit(0.U(8.W))

    // 状态编码：
    // bit 0: 系统空闲
    // bit 1: 系统忙碌
    // bit 2: 系统错误
    // bit 3: 内存错误
    // bit 4: 软复位状态
    // bit 5-7: 保留

    status := Cat(
      0.U(3.W), // 保留位
      softReset, // 软复位状态
      false.B, // 内存错误（可以添加检测逻辑）
      false.B, // 系统错误（可以添加检测逻辑）
      io.debug.systemBusy, // 系统忙碌
      !io.debug.systemBusy // 系统空闲
    )

    status
  }

  io.debug.systemStatus := systemStatus

  // ===== 中断生成 =====

  val interrupts = withClock(io.clock) {
    // 任务完成中断
    val taskCompleteInt = io.queue_resps.map(qr => qr.valid && qr.ready).reduce(_ || _)

    // 系统错误中断（可以添加错误检测逻辑）
    val systemError = false.B

    // 内存错误中断（可以添加内存错误检测逻辑）
    val memoryError = false.B

    val intBundle = Wire(new Bundle {
      val taskComplete = Bool()
      val systemError = Bool()
      val memoryError = Bool()
    })
    intBundle.taskComplete := taskCompleteInt
    intBundle.systemError := systemError
    intBundle.memoryError := memoryError
    intBundle
  }

  io.interrupts <> interrupts

  // ===== 调试支持 =====

  // 调试端口（可选）
  val debugPort = withClock(io.clock) {
    val debugData = RegInit(0.U(32.W))
    val debugValid = RegInit(false.B)

    // 可以通过外部接口设置调试数据
    // 这里提供基本的调试支持

    new Bundle {
      val data = debugData
      val valid = debugValid
    }
  }

  // ===== 电源管理 =====

  // 时钟门控支持（可选）
  val clockGating = withClock(io.clock) {
    val clockEnable = RegInit(true.B)

    // 当系统空闲时可以关闭时钟以节省功耗
    when(!io.debug.systemBusy) {
      clockEnable := false.B
    }.otherwise {
      clockEnable := true.B
    }

    clockEnable
  }

  // ===== 断言和验证 =====

  // 系统级断言
  assert(
    !(io.debug.systemBusy && !io.clock.asUInt.orR),
    "System should not be busy when clock is not running"
  )

  assert(
    !(io.debug.activeComputeUnits > parameter.numComputeUnits.U),
    "Active compute units should not exceed maximum"
  )

  assert(
    !(io.debug.activeWorkGroups > parameter.numWorkGroups.U),
    "Active work groups should not exceed maximum"
  )

  // ===== 初始化 =====

  // 系统初始化完成信号
  val initComplete = withClock(io.clock) {
    val initReg = RegInit(false.B)
    when(!systemReset) {
      initReg := true.B
    }
    initReg
  }
}

/** OGPU System Top Factory
  *
  * 用于创建不同配置的OGPU系统
  */
object OGPUSystemTop {

  /** 创建最小配置的系统 */
  def createMinimal(): OGPUSystemTop = {
    Module(new OGPUSystemTop(SystemConfigs.Minimal))
  }

  /** 创建小型配置的系统 */
  def createSmall(): OGPUSystemTop = {
    Module(new OGPUSystemTop(SystemConfigs.Small))
  }

  /** 创建中型配置的系统 */
  def createMedium(): OGPUSystemTop = {
    Module(new OGPUSystemTop(SystemConfigs.Medium))
  }

  /** 创建大型配置的系统 */
  def createLarge(): OGPUSystemTop = {
    Module(new OGPUSystemTop(SystemConfigs.Large))
  }

  /** 创建测试配置的系统 */
  def createTest(): OGPUSystemTop = {
    Module(new OGPUSystemTop(SystemConfigs.Test))
  }

  /** 根据配置名称创建系统 */
  def create(configName: String): Option[OGPUSystemTop] = {
    SystemConfigs.getConfig(configName).map { config =>
      Module(new OGPUSystemTop(config))
    }
  }
}
