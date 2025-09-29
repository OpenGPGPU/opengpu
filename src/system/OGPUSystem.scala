package ogpu.system

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}
import org.chipsalliance.tilelink.bundle._
import ogpu.core._
import ogpu.dispatcher._

/** OGPU System Parameters
  *
  * 系统级参数配置，包含所有子模块的参数
  */
case class OGPUSystemParameter(
  // Core parameters
  instructionSets:   Set[String] = Set("rv_i", "rv_m", "rv_a", "rv_f", "rv_d", "rv_v"),
  pipelinedMul:      Boolean = true,
  fenceIFlushDCache: Boolean = false,
  warpNum:           Int = 4,
  xLen:              Int = 64,
  vLen:              Int = 128,
  vaddrBitsExtended: Int = 40,
  useAsyncReset:     Boolean = false,

  // Dispatcher parameters
  numQueues:       Int = 4,
  numJobs:         Int = 2,
  numWorkGroups:   Int = 2,
  numComputeUnits: Int = 2,
  warpSize:        Int = 32,
  bufferNum:       Int = 8)
    extends SerializableModuleParameter {
  // Derived parameters
  val coreParam = OGPUParameter(
    instructionSets = instructionSets,
    pipelinedMul = pipelinedMul,
    fenceIFlushDCache = fenceIFlushDCache,
    warpNum = warpNum,
    xLen = xLen,
    vLen = vLen,
    memDataWidth = 64, // Set memory data width to 64 bits
    vaddrBitsExtended = vaddrBitsExtended,
    useAsyncReset = useAsyncReset
  )

  val dispatcherParam = DispatcherParameter(
    useAsyncReset = useAsyncReset,
    clockGate = false,
    bufferNum = bufferNum
  )

  val workGroupDispatcherParam = WorkGroupDispatcherParameter(
    useAsyncReset = useAsyncReset,
    clockGate = false,
    warpSize = warpSize
  )

  val queueJobParam = QueueJobParameter(
    useAsyncReset = useAsyncReset,
    clockGate = false,
    numQueuePorts = numQueues,
    numJobPorts = numJobs
  )

  val jobWorkGroupParam = JobWorkGroupParameter(
    useAsyncReset = useAsyncReset,
    clockGate = false,
    numJobPorts = numJobs,
    numWGPorts = numWorkGroups
  )

  val workgroupCUParam = WorkgroupCUParameter(
    useAsyncReset = useAsyncReset,
    clockGate = false,
    numWGPorts = numWorkGroups,
    numCUPorts = numComputeUnits
  )
}

object OGPUSystemParameter {
  implicit def rwP: upickle.default.ReadWriter[OGPUSystemParameter] = upickle.default.macroRW[OGPUSystemParameter]
}

/** OGPU System Interface
  *
  * 系统级接口，包含所有外部连接
  */
class OGPUSystemInterface(parameter: OGPUSystemParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())

  // Queue interfaces (from host)
  val queues = Vec(parameter.numQueues, Flipped(DecoupledIO(new QueueBundle)))
  val queue_resps = Vec(parameter.numQueues, DecoupledIO(new QueueRespBundle))

  // Memory interface (to system memory)
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

  // Debug and monitoring
  val debug = Output(new Bundle {
    val systemBusy = Bool()
    val activeComputeUnits = Vec(parameter.numComputeUnits, Bool())
    val activeWorkGroups = Vec(parameter.numWorkGroups, Bool())
    val queueUtilization = Vec(parameter.numQueues, Bool())
    val systemStatus = UInt(8.W)
    val performanceCounters = new Bundle {
      val totalCycles = UInt(64.W)
      val activeCycles = UInt(64.W)
      val completedTasks = UInt(32.W)
      val memoryTransactions = UInt(32.W)
    }
  })
}

/** OGPU System
  *
  * 完整的OGPU系统，集成所有dispatcher和compute unit
  */
@instantiable
class OGPUSystem(val parameter: OGPUSystemParameter)
    extends FixedIORawModule(new OGPUSystemInterface(parameter))
    with SerializableModule[OGPUSystemParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // ===== 实例化所有模块 =====

  // Queue to Job interconnector
  val queueJobInterconnector = Module(new QueueJobInterconnector(parameter.queueJobParam))

  // Job dispatcher
  val jobDispatcher = Module(new JobDispatcher(parameter.dispatcherParam))

  // Job to WorkGroup interconnector
  val jobWorkGroupInterconnector = Module(new JobWorkGroupInterconnector(parameter.jobWorkGroupParam))

  // WorkGroup dispatchers
  val workGroupDispatchers = Seq.fill(parameter.numWorkGroups) {
    Module(new WorkGroupDispatcher(parameter.workGroupDispatcherParam))
  }

  // WorkGroup to ComputeUnit interconnector
  val workgroupCUInterconnector = Module(new WorkgroupCUInterconnector(parameter.workgroupCUParam))

  // Compute Units
  val computeUnits = Seq.fill(parameter.numComputeUnits) {
    Module(new ComputeUnit(parameter.coreParam))
  }

  // ===== 时钟和复位连接 =====

  // 连接所有模块的时钟和复位
  queueJobInterconnector.io.clock := io.clock
  queueJobInterconnector.io.reset := io.reset

  jobDispatcher.io.clock := io.clock
  jobDispatcher.io.reset := io.reset

  jobWorkGroupInterconnector.io.clock := io.clock
  jobWorkGroupInterconnector.io.reset := io.reset

  workGroupDispatchers.foreach { wgd =>
    wgd.io.clock := io.clock
    wgd.io.reset := io.reset
  }

  workgroupCUInterconnector.io.clock := io.clock
  workgroupCUInterconnector.io.reset := io.reset

  computeUnits.foreach { cu =>
    cu.io.clock := io.clock
    cu.io.reset := io.reset
  }

  // ===== Queue到Job的连接 =====

  // 连接队列接口到Queue-Job互连器
  for (i <- 0 until parameter.numQueues) {
    queueJobInterconnector.io.queue(i) <> io.queues(i)
    queueJobInterconnector.io.queue_resp(i) <> io.queue_resps(i)
  }

  // 连接Queue-Job互连器到Job Dispatcher
  // 只使用第一个job端口，其他端口保持未连接状态
  jobDispatcher.io.queue <> queueJobInterconnector.io.job(0)
  queueJobInterconnector.io.job_resp(0) <> jobDispatcher.io.queue_resp

  // 将未使用的job端口设置为非活跃状态
  for (i <- 1 until parameter.numJobs) {
    queueJobInterconnector.io.job(i).ready := false.B
    queueJobInterconnector.io.job_resp(i).valid := false.B
    queueJobInterconnector.io.job_resp(i).bits := 0.U.asTypeOf(queueJobInterconnector.io.job_resp(i).bits)
  }

  // ===== Job到WorkGroup的连接 =====

  // 连接Job Dispatcher到Job-WorkGroup互连器
  // 只使用第一个job端口，其他端口保持未连接状态
  jobWorkGroupInterconnector.io.job(0) <> jobDispatcher.io.task
  jobWorkGroupInterconnector.io.job_resp(0) <> jobDispatcher.io.task_resp

  // 将未使用的job端口设置为非活跃状态
  for (i <- 1 until parameter.numJobs) {
    jobWorkGroupInterconnector.io.job(i).valid := false.B
    jobWorkGroupInterconnector.io.job(i).bits := 0.U.asTypeOf(jobWorkGroupInterconnector.io.job(i).bits)
    jobWorkGroupInterconnector.io.job_resp(i).ready := false.B
  }

  // 连接Job-WorkGroup互连器到WorkGroup Dispatchers
  for (i <- 0 until parameter.numWorkGroups) {
    workGroupDispatchers(i).io.workgroup_task <> jobWorkGroupInterconnector.io.wg(i)
    jobWorkGroupInterconnector.io.wg_resp(i) <> workGroupDispatchers(i).io.workgroup_task_resp
  }

  // ===== WorkGroup到ComputeUnit的连接 =====

  // 连接WorkGroup Dispatchers到WorkGroup-CU互连器
  for (i <- 0 until parameter.numWorkGroups) {
    workgroupCUInterconnector.io.wg(i) <> workGroupDispatchers(i).io.warp_task
    workGroupDispatchers(i).io.warp_task_resp <> workgroupCUInterconnector.io.wg_resp(i)
  }

  // 连接WorkGroup-CU互连器到Compute Units
  // 需要适配器来转换WarpTaskBundle到CuTaskBundle
  for (i <- 0 until parameter.numComputeUnits) {
    // 创建适配器来转换Bundle类型
    val cuTaskAdapter = Wire(new CuTaskBundle(32, parameter.coreParam.warpNum, 4, parameter.coreParam.xLen))

    // 从WarpTaskBundle转换到CuTaskBundle
    // 设置默认的mask（所有线程活跃）
    cuTaskAdapter.mask := VecInit(Seq.fill(32)(true.B))

    // 设置PC（使用kernel_object作为入口地址）
    cuTaskAdapter.pc := workgroupCUInterconnector.io.cu(i).bits.kernel_object(31, 0)

    // 设置VGPRs（使用workgroup相关信息）
    cuTaskAdapter.vgprs(0) := workgroupCUInterconnector.io.cu(i).bits.workgroup_id_x
    cuTaskAdapter.vgprs(1) := workgroupCUInterconnector.io.cu(i).bits.workgroup_id_y
    cuTaskAdapter.vgprs(2) := workgroupCUInterconnector.io.cu(i).bits.workgroup_id_z
    cuTaskAdapter.vgprs(3) := 0.U // 保留
    cuTaskAdapter.vgpr_num := 3.U

    // 设置SGPRs（使用grid和workgroup信息）
    cuTaskAdapter.sgprs(0) := workgroupCUInterconnector.io.cu(i).bits.grid_size_x
    cuTaskAdapter.sgprs(1) := workgroupCUInterconnector.io.cu(i).bits.grid_size_y
    cuTaskAdapter.sgprs(2) := workgroupCUInterconnector.io.cu(i).bits.grid_size_z
    cuTaskAdapter.sgprs(3) := workgroupCUInterconnector.io.cu(i).bits.workgroup_size_x(15, 0).asUInt
    cuTaskAdapter.sgprs(4) := workgroupCUInterconnector.io.cu(i).bits.workgroup_size_y(15, 0).asUInt
    cuTaskAdapter.sgprs(5) := workgroupCUInterconnector.io.cu(i).bits.workgroup_size_z(15, 0).asUInt
    cuTaskAdapter.sgprs(6) := workgroupCUInterconnector.io.cu(i).bits.private_segment_size
    cuTaskAdapter.sgprs(7) := workgroupCUInterconnector.io.cu(i).bits.group_segment_size
    cuTaskAdapter.sgprs(8) := workgroupCUInterconnector.io.cu(i).bits.kernargs_address(31, 0)
    cuTaskAdapter.sgprs(9) := workgroupCUInterconnector.io.cu(i).bits.kernargs_address(63, 32)
    for (j <- 10 until 16) {
      cuTaskAdapter.sgprs(j) := 0.U
    }
    cuTaskAdapter.sgpr_num := 10.U

    // 连接到ComputeUnit
    computeUnits(i).io.task.valid := workgroupCUInterconnector.io.cu(i).valid
    computeUnits(i).io.task.bits := cuTaskAdapter
    workgroupCUInterconnector.io.cu(i).ready := computeUnits(i).io.task.ready

    // ComputeUnit没有task_resp接口，这里需要创建一个响应
    workgroupCUInterconnector.io.cu_resp(i).valid := computeUnits(i).io.idle
    workgroupCUInterconnector.io.cu_resp(i).bits.finish := computeUnits(i).io.idle
  }

  // ===== 内存接口连接 =====

  // 将所有Compute Unit的内存接口连接到系统内存接口
  // 这里使用简单的仲裁器，实际实现中可能需要更复杂的互连
  val memoryArbiter = Module(new MemoryArbiter(parameter.numComputeUnits))

  for (i <- 0 until parameter.numComputeUnits) {
    memoryArbiter.io.computeUnitMem(i) <> computeUnits(i).io.memory
  }

  memoryArbiter.io.systemMem <> io.memory

  // ===== 调试信号生成 =====

  // 系统忙碌状态
  val anyComputeUnitBusy = computeUnits.map(_.io.busy).reduce(_ || _)
  val anyWorkGroupBusy = workGroupDispatchers.map(_.io.workgroup_task.valid).reduce(_ || _)
  val anyJobBusy = jobDispatcher.io.task.valid

  io.debug.systemBusy := anyComputeUnitBusy || anyWorkGroupBusy || anyJobBusy

  // 活跃的Compute Unit
  for (i <- 0 until parameter.numComputeUnits) {
    io.debug.activeComputeUnits(i) := computeUnits(i).io.busy
  }

  // 活跃的WorkGroup
  for (i <- 0 until parameter.numWorkGroups) {
    io.debug.activeWorkGroups(i) := workGroupDispatchers(i).io.workgroup_task.valid
  }

  // 队列利用率
  for (i <- 0 until parameter.numQueues) {
    io.debug.queueUtilization(i) := io.queues(i).valid && io.queues(i).ready
  }

  // 系统状态
  io.debug.systemStatus := Cat(
    false.B, // 位7: 保留
    false.B, // 位6: 保留
    false.B, // 位5: 保留
    false.B, // 位4: 保留
    false.B, // 位3: 内存错误（可以添加检测逻辑）
    false.B, // 位2: 系统错误（可以添加检测逻辑）
    io.debug.systemBusy, // 位1: 系统忙碌
    !io.debug.systemBusy // 位0: 系统空闲
  )

  // ===== 性能计数器 =====

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

  // 内存事务计数（通过内存接口检测）
  val memoryTransactionSignal = io.memory.a.valid && io.memory.a.ready
  when(memoryTransactionSignal) {
    memoryTransactions := memoryTransactions + 1.U
  }

  // 连接性能计数器到debug接口
  io.debug.performanceCounters.totalCycles := totalCycles
  io.debug.performanceCounters.activeCycles := activeCycles
  io.debug.performanceCounters.completedTasks := completedTasks
  io.debug.performanceCounters.memoryTransactions := memoryTransactions
}

/** Memory Arbiter
  *
  * 简单的内存仲裁器，用于多个Compute Unit共享系统内存接口
  */
class MemoryArbiter(numPorts: Int) extends Module {
  val io = IO(new Bundle {
    val computeUnitMem = Vec(
      numPorts,
      Flipped(
        new TLLink(
          TLLinkParameter(
            addressWidth = 34,
            sourceWidth = 8,
            sinkWidth = 8,
            dataWidth = 64,
            sizeWidth = 4,
            hasBCEChannels = false
          )
        )
      )
    )
    val systemMem = new TLLink(
      TLLinkParameter(
        addressWidth = 34,
        sourceWidth = 8,
        sinkWidth = 8,
        dataWidth = 64,
        sizeWidth = 4,
        hasBCEChannels = false
      )
    )
  })

  // 简单的轮询仲裁器
  val arbiter = Module(
    new RRArbiter(
      new Bundle {
        val a = new org.chipsalliance.tilelink.bundle.TLChannelA(
          org.chipsalliance.tilelink.bundle.TileLinkChannelAParameter(
            addressWidth = 34,
            sourceWidth = 8,
            dataWidth = 64,
            sizeWidth = 4
          )
        )
        val sourceId = UInt(8.W)
      },
      numPorts
    )
  )

  // 连接A通道（从ComputeUnit到SystemMem）
  for (i <- 0 until numPorts) {
    // 由于computeUnitMem是Flipped，所以方向相反
    arbiter.io.in(i).bits.a.opcode := io.computeUnitMem(i).a.bits.opcode
    arbiter.io.in(i).bits.a.param := io.computeUnitMem(i).a.bits.param
    arbiter.io.in(i).bits.a.size := io.computeUnitMem(i).a.bits.size
    arbiter.io.in(i).bits.a.source := io.computeUnitMem(i).a.bits.source
    arbiter.io.in(i).bits.a.address := io.computeUnitMem(i).a.bits.address
    arbiter.io.in(i).bits.a.mask := io.computeUnitMem(i).a.bits.mask
    arbiter.io.in(i).bits.a.data := io.computeUnitMem(i).a.bits.data
    arbiter.io.in(i).bits.a.corrupt := io.computeUnitMem(i).a.bits.corrupt
    arbiter.io.in(i).valid := io.computeUnitMem(i).a.valid
    io.computeUnitMem(i).a.ready := arbiter.io.in(i).ready
    arbiter.io.in(i).bits.sourceId := i.U
  }

  // 连接仲裁器输出到系统内存
  io.systemMem.a.bits.opcode := arbiter.io.out.bits.a.opcode
  io.systemMem.a.bits.param := arbiter.io.out.bits.a.param
  io.systemMem.a.bits.size := arbiter.io.out.bits.a.size
  io.systemMem.a.bits.source := arbiter.io.out.bits.a.source
  io.systemMem.a.bits.address := arbiter.io.out.bits.a.address
  io.systemMem.a.bits.mask := arbiter.io.out.bits.a.mask
  io.systemMem.a.bits.data := arbiter.io.out.bits.a.data
  io.systemMem.a.bits.corrupt := arbiter.io.out.bits.a.corrupt
  io.systemMem.a.valid := arbiter.io.out.valid
  arbiter.io.out.ready := io.systemMem.a.ready

  // 连接D通道（需要根据source ID路由，从SystemMem到ComputeUnit）
  val dRouter = Module(new DDRouter(numPorts))
  dRouter.io.systemD <> io.systemMem.d
  for (i <- 0 until numPorts) {
    // 由于computeUnitMem是Flipped，D通道连接方向需要调整
    io.computeUnitMem(i).d.valid := dRouter.io.computeUnitD(i).valid
    io.computeUnitMem(i).d.bits := dRouter.io.computeUnitD(i).bits
    dRouter.io.computeUnitD(i).ready := io.computeUnitMem(i).d.ready
  }

}

/** D Channel Router
  *
  * 根据source ID将D通道响应路由到正确的Compute Unit
  */
class DDRouter(numPorts: Int) extends Module {
  val io = IO(new Bundle {
    val systemD = Flipped(
      DecoupledIO(
        new org.chipsalliance.tilelink.bundle.TLChannelD(
          org.chipsalliance.tilelink.bundle.TileLinkChannelDParameter(
            sourceWidth = 8,
            sinkWidth = 8,
            dataWidth = 64,
            sizeWidth = 4
          )
        )
      )
    )
    val computeUnitD = Vec(
      numPorts,
      DecoupledIO(
        new org.chipsalliance.tilelink.bundle.TLChannelD(
          org.chipsalliance.tilelink.bundle.TileLinkChannelDParameter(
            sourceWidth = 8,
            sinkWidth = 8,
            dataWidth = 64,
            sizeWidth = 4
          )
        )
      )
    )
  })

  // 简单的路由逻辑
  val sourceId = io.systemD.bits.source(log2Ceil(numPorts) - 1, 0)

  // 默认值设置
  for (i <- 0 until numPorts) {
    io.computeUnitD(i).valid := false.B
    io.computeUnitD(i).bits := DontCare
  }

  // 路由到正确的端口
  io.computeUnitD(sourceId).valid := io.systemD.valid
  io.computeUnitD(sourceId).bits := io.systemD.bits
  io.systemD.ready := io.computeUnitD(sourceId).ready
}
