package ogpu.system

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.SerializableModule
import ogpu.dispatcher._

/** OGPU Main Module
  *
  * 这是OGPU系统的主入口模块，提供最顶层的接口 可以根据不同的配置参数实例化不同的系统
  */
@instantiable
class OGPU(val parameter: OGPUSystemParameter = SystemConfigs.Small)
    extends Module
    with SerializableModule[OGPUSystemParameter]
    with Public {

  val io = IO(new Bundle {
    // 时钟和复位
    val clock = Input(Clock())
    val reset = Input(Bool())

    // 队列接口
    val queues = Vec(parameter.numQueues, Flipped(DecoupledIO(new QueueBundle)))
    val queue_resps = Vec(parameter.numQueues, DecoupledIO(new QueueRespBundle))

    // 内存接口
    val memory = new org.chipsalliance.tilelink.bundle.TLLink(
      org.chipsalliance.tilelink.bundle.TLLinkParameter(
        addressWidth = 34,
        sourceWidth = 8,
        sinkWidth = 8,
        dataWidth = 64,
        sizeWidth = 4,
        hasBCEChannels = false
      )
    )

    // 调试接口
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

  // 实例化顶层系统
  val systemTop = Module(new OGPUSystemTop(parameter))

  // 连接所有接口
  systemTop.io.clock := io.clock
  systemTop.io.reset := io.reset
  systemTop.io.queues <> io.queues
  systemTop.io.queue_resps <> io.queue_resps
  systemTop.io.memory <> io.memory
  systemTop.io.debug <> io.debug
  systemTop.io.interrupts <> io.interrupts
}

/** OGPU Factory Object
  *
  * 提供便捷的工厂方法创建不同配置的OGPU实例
  */
object OGPU {

  /** 创建最小配置的OGPU */
  def minimal(): OGPU = {
    Module(new OGPU(SystemConfigs.Minimal))
  }

  /** 创建小型配置的OGPU */
  def small(): OGPU = {
    Module(new OGPU(SystemConfigs.Small))
  }

  /** 创建中型配置的OGPU */
  def medium(): OGPU = {
    Module(new OGPU(SystemConfigs.Medium))
  }

  /** 创建大型配置的OGPU */
  def large(): OGPU = {
    Module(new OGPU(SystemConfigs.Large))
  }

  /** 创建最大配置的OGPU */
  def maximum(): OGPU = {
    Module(new OGPU(SystemConfigs.Maximum))
  }

  /** 创建向量优化配置的OGPU */
  def vectorOptimized(): OGPU = {
    Module(new OGPU(SystemConfigs.VectorOptimized))
  }

  /** 创建低功耗配置的OGPU */
  def lowPower(): OGPU = {
    Module(new OGPU(SystemConfigs.LowPower))
  }

  /** 创建测试配置的OGPU */
  def test(): OGPU = {
    Module(new OGPU(SystemConfigs.Test))
  }

  /** 根据配置名称创建OGPU */
  def create(configName: String): Option[OGPU] = {
    SystemConfigs.getConfig(configName).map { config =>
      Module(new OGPU(config))
    }
  }

  /** 使用自定义配置创建OGPU */
  def create(config: OGPUSystemParameter): OGPU = {
    Module(new OGPU(config))
  }

  /** 获取所有可用的配置名称 */
  def getAvailableConfigs: Seq[String] = {
    SystemConfigs.getAllConfigs.keys.toSeq
  }

  /** 打印配置信息 */
  def printConfig(config: OGPUSystemParameter): Unit = {
    println(SystemConfigs.configToString(config))
  }

  /** 验证配置 */
  def validateConfig(config: OGPUSystemParameter): Boolean = {
    SystemConfigs.validateConfig(config)
  }

  /** 根据配置名称获取配置 */
  def getConfig(configName: String): Option[OGPUSystemParameter] = {
    SystemConfigs.getConfig(configName)
  }
}
