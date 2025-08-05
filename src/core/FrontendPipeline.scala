package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.SerializableModule

/** 前端流水线接口
  *
  * 封装了前端的所有接口
  */
class FrontendPipelineInterface(parameter: OGPUParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())

  // Warp管理接口
  val warp = new Bundle {
    val start = Flipped(DecoupledIO(new CuTaskBundle(32, parameter.warpNum, 4, parameter.xLen)))
    val regInitDone = Input(Bool())
    val finish = Input(Valid(UInt(log2Ceil(parameter.warpNum).W)))
  }

  // 指令输出
  val instruction = DecoupledIO(new Bundle {
    val instruction = new InstructionBundle(parameter.warpNum, 32)
    val coreResult = new CoreDecoderInterface(parameter)
    val fpuResult = new FPUDecoderInterface(parameter)
    val rvc = Bool()
  })

  // 分支解析接口
  val branchUpdate = Flipped(ValidIO(new BranchResultBundle(parameter)))

  // 内存接口
  val memory = new Bundle {
    val tilelink = new Bundle {
      val a = DecoupledIO(new Bundle {
        val opcode = UInt(3.W)
        val param = UInt(3.W)
        val size = UInt(4.W)
        val source = UInt(8.W)
        val address = UInt(34.W)
        val mask = UInt(8.W)
        val data = UInt(64.W)
        val corrupt = Bool()
      })
      val d = Flipped(DecoupledIO(new Bundle {
        val opcode = UInt(3.W)
        val param = UInt(3.W)
        val size = UInt(4.W)
        val source = UInt(8.W)
        val sink = UInt(8.W)
        val denied = Bool()
        val data = UInt(64.W)
        val corrupt = Bool()
      }))
    }
  }

  // 控制信号
  val flush = Input(Bool())
  val stall = Input(Bool())
}

/** 前端流水线
  *
  * 封装了WarpFrontend、Frontend、DecodePipe
  */
class FrontendPipeline(val parameter: OGPUParameter)
    extends FixedIORawModule(new FrontendPipelineInterface(parameter))
    with SerializableModule[OGPUParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // 子模块实例化
  val warpScheduler = Module(
    new WarpScheduler(
      WarpParameter(
        useAsyncReset = parameter.useAsyncReset,
        clockGate = parameter.clockGate,
        warpNum = parameter.warpNum,
        stackDepth = 8,
        xLen = parameter.xLen,
        dimNum = 4,
        addrBits = parameter.vaddrBitsExtended,
        pgLevels = 2,
        asidBits = 9,
        threadNum = 32
      )
    )
  )

  val warpFrontend = Module(new WarpFrontend(parameter))
  val frontend = Module(
    new Frontend(
      FrontendParameter(
        warpNum = parameter.warpNum,
        useAsyncReset = parameter.useAsyncReset,
        clockGate = parameter.clockGate,
        xLen = parameter.xLen,
        usingAtomics = false,
        usingDataScratchpad = false,
        usingVM = true,
        usingCompressed = true,
        itlbNSets = 4,
        itlbNWays = 2,
        itlbNSectors = 1,
        itlbNSuperpageEntries = 2,
        blockBytes = 64,
        iCacheNSets = 64,
        iCacheNWays = 4,
        iCachePrefetch = true,
        nPages = 32,
        nRAS = 8,
        nPMPs = 0,
        paddrBits = 40,
        pgLevels = 4,
        asidBits = 9,
        legal = chisel3.util.experimental.BitSet.empty,
        cacheable = chisel3.util.experimental.BitSet.empty,
        read = chisel3.util.experimental.BitSet.empty,
        write = chisel3.util.experimental.BitSet.empty,
        putPartial = chisel3.util.experimental.BitSet.empty,
        logic = chisel3.util.experimental.BitSet.empty,
        arithmetic = chisel3.util.experimental.BitSet.empty,
        exec = chisel3.util.experimental.BitSet.empty,
        sideEffects = chisel3.util.experimental.BitSet.empty
      )
    )
  )

  val decodePipe = Module(new DecodePipe(parameter))

  // 连接时钟和复位
  warpScheduler.io.clock := io.clock
  warpScheduler.io.reset := io.reset
  warpFrontend.io.clock := io.clock
  warpFrontend.io.reset := io.reset
  frontend.io.clock := io.clock
  frontend.io.reset := io.reset
  decodePipe.io.clock := io.clock
  decodePipe.io.reset := io.reset

  // 连接Warp管理
  warpScheduler.io.warp_cmd <> io.warp.start
  warpFrontend.io.reg_init_done := io.warp.regInitDone
  warpFrontend.io.warp_finish := io.warp.finish

  // 连接前端
  frontend.io.nonDiplomatic.cpu.req <> warpFrontend.io.frontend_req
  warpFrontend.io.frontend_resp <> frontend.io.nonDiplomatic.cpu.resp

  // 连接内存接口
  io.memory.tilelink.a <> frontend.io.instructionFetchTileLink.a
  frontend.io.instructionFetchTileLink.d <> io.memory.tilelink.d

  // 连接解码管道
  decodePipe.io.instruction <> warpFrontend.io.decode

  // 连接分支解析
  warpFrontend.io.branch_update.valid := io.branchUpdate.valid
  warpFrontend.io.branch_update.bits.wid := io.branchUpdate.bits.wid
  warpFrontend.io.branch_update.bits.pc := io.branchUpdate.bits.pc
  warpFrontend.io.branch_update.bits.target := io.branchUpdate.bits.target

  // 连接指令输出
  io.instruction.valid := decodePipe.io.coreResult.valid
  io.instruction.bits.instruction := decodePipe.io.instruction_out
  io.instruction.bits.coreResult := decodePipe.io.coreResult.bits
  io.instruction.bits.fpuResult := decodePipe.io.fpuResult.bits
  io.instruction.bits.rvc := decodePipe.io.rvc

  // 流水线控制
  decodePipe.io.coreResult.ready := io.instruction.ready && !io.stall
  decodePipe.io.fpuResult.ready := io.instruction.ready && !io.stall

  // 前端控制 - 移除错误的连接，这些是输出端口
  // frontend.io.nonDiplomatic.ptw.req.valid := false.B
  // frontend.io.nonDiplomatic.ptw.resp.valid := false.B
  // frontend.io.nonDiplomatic.ptw.ptbr := 0.U.asTypeOf(frontend.io.nonDiplomatic.ptw.ptbr)
}
