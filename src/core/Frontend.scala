// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2012-2014 The Regents of the University of California
// SPDX-FileCopyrightText: 2016-2017 SiFive, Inc
// SPDX-FileCopyrightText: 2024 Jiuyang Liu <liu@jiuyang.me>
package ogpu.core

import chisel3._
import chisel3.experimental.hierarchy.{instantiable, Instantiate}
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}
import chisel3.util._
import chisel3.util.circt.ClockGate
import chisel3.util.experimental.BitSet
import org.chipsalliance.amba.axi4.bundle.{AXI4BundleParameter, AXI4ROIrrevocable, AXI4RWIrrevocable}

object FrontendParameter {
  implicit def bitSetP: upickle.default.ReadWriter[BitSet] = upickle.default
    .readwriter[String]
    .bimap[BitSet](
      bs => bs.terms.map("b" + _.rawString).mkString("\n"),
      str => if (str.isEmpty) BitSet.empty else BitSet.fromString(str)
    )

  implicit def rwP: upickle.default.ReadWriter[FrontendParameter] = upickle.default.macroRW[FrontendParameter]
}

case class FrontendParameter(
  // must be false, since resetVector will be aligned here.
  warpNum:               Int,
  useAsyncReset:         Boolean,
  clockGate:             Boolean,
  xLen:                  Int,
  usingAtomics:          Boolean,
  usingDataScratchpad:   Boolean,
  usingVM:               Boolean,
  usingCompressed:       Boolean,
  itlbNSets:             Int,
  itlbNWays:             Int,
  itlbNSectors:          Int,
  itlbNSuperpageEntries: Int,
  blockBytes:            Int,
  iCacheNSets:           Int,
  iCacheNWays:           Int,
  iCachePrefetch:        Boolean,
  nPages:                Int,
  nRAS:                  Int,
  nPMPs:                 Int,
  paddrBits:             Int,
  pgLevels:              Int,
  asidBits:              Int,
  legal:                 BitSet,
  cacheable:             BitSet,
  read:                  BitSet,
  write:                 BitSet,
  putPartial:            BitSet,
  logic:                 BitSet,
  arithmetic:            BitSet,
  exec:                  BitSet,
  sideEffects:           BitSet)
    extends SerializableModuleParameter {
  // static now
  def hasCorrectable:        Boolean = false
  def usingHypervisor:       Boolean = false
  def hasUncorrectable:      Boolean = false
  def usingAtomicsOnlyForIO: Boolean = false
  def itimParameter:         Option[AXI4BundleParameter] = None

  // calculate
  def usingAtomicsInCache:        Boolean = usingAtomics && !usingAtomicsOnlyForIO
  private def vpnBitsExtended:    Int = vpnBits + (if (vaddrBits < xLen) 1 + (if (usingHypervisor) 1 else 0) else 0)
  def vaddrBitsExtended:          Int = vpnBitsExtended + pgIdxBits
  def maxHypervisorExtraAddrBits: Int = 2
  def hypervisorExtraAddrBits:    Int = if (usingHypervisor) maxHypervisorExtraAddrBits else 0
  def pgLevelBits:                Int = 10 - log2Ceil(xLen / 32)
  def maxSVAddrBits:              Int = pgIdxBits + pgLevels * pgLevelBits
  def maxHVAddrBits:              Int = maxSVAddrBits + hypervisorExtraAddrBits
  def vaddrBits: Int = if (usingVM) {
    val v = maxHVAddrBits
    require(v > paddrBits)
    v
  } else {
    // since virtual addresses sign-extend but physical addresses
    // zero-extend, make room for a zero sign bit for physical addresses
    (paddrBits + 1).min(xLen)
  }
  def coreInstBits: Int = if (usingCompressed) 16 else 32
  def vpnBits:      Int = vaddrBits - pgIdxBits
  def maxPAddrBits: Int = xLen match {
    case 32 => 34
    case 64 => 56
  }
  def pgIdxBits:  Int = 12
  val fetchWidth: Int = if (usingCompressed) 2 else 1
  def fetchBytes: Int = 4
  val coreInstBytes = (if (usingCompressed) 16 else 32) / 8
  def resetVectorBits: Int = paddrBits
  val rowBits:         Int = fetchBytes * 8
  val instructionFetchParameter: AXI4BundleParameter = AXI4BundleParameter(
    idWidth = 1,
    dataWidth = rowBits,
    addrWidth = paddrBits,
    userReqWidth = 0,
    userDataWidth = 0,
    userRespWidth = 0,
    hasAW = false,
    hasW = false,
    hasB = false,
    hasAR = true,
    hasR = true,
    supportId = true,
    supportRegion = false,
    supportLen = true,
    supportSize = true,
    supportBurst = true,
    supportLock = false,
    supportCache = false,
    supportQos = false,
    supportStrb = false,
    supportResp = false,
    supportProt = false
  )

  def icacheParameter: ICacheParameter = ICacheParameter(
    useAsyncReset = useAsyncReset,
    prefetch = iCachePrefetch,
    nSets = iCacheNSets,
    nWays = iCacheNWays,
    blockBytes = blockBytes,
    usingVM = usingVM,
    vaddrBits = vaddrBits,
    paddrBits = paddrBits
  )

  def tlbParameter: TLBParameter = TLBParameter(
    useAsyncReset = useAsyncReset,
    xLen = xLen,
    nSets = itlbNSets,
    nWays = itlbNWays,
    nSectors = itlbNSectors,
    nSuperpageEntries = itlbNSuperpageEntries,
    asidBits = asidBits,
    pgLevels = pgLevels,
    usingAtomics = usingAtomics,
    usingDataScratchpad = usingDataScratchpad,
    usingAtomicsOnlyForIO = usingAtomicsOnlyForIO,
    usingVM = usingVM,
    usingAtomicsInCache = usingAtomicsInCache,
    paddrBits = paddrBits,
    isITLB = true
  )

  // entry = 5
  def fetchQueueParameter: FetchQueueParameter = FetchQueueParameter(
    // static to be false.
    useAsyncReset = false,
    warpNum = warpNum,
    entries = 5,
    vaddrBits = vaddrBits,
    vaddrBitsExtended = vaddrBitsExtended,
    coreInstBits = coreInstBits,
    fetchWidth = fetchWidth
  )
}

class FrontendInterface(parameter: FrontendParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(if (parameter.useAsyncReset) AsyncReset() else Bool())
  val resetVector = Input(Const(UInt(parameter.resetVectorBits.W)))
  val nonDiplomatic = new FrontendBundle(
    parameter.warpNum,
    parameter.vaddrBitsExtended,
    parameter.vaddrBits,
    parameter.asidBits,
    parameter.coreInstBits,
    parameter.vpnBits,
    parameter.paddrBits,
    parameter.pgLevels,
    parameter.xLen,
    parameter.maxPAddrBits,
    parameter.pgIdxBits,
    parameter.hasCorrectable,
    parameter.hasUncorrectable,
    parameter.fetchWidth
  )
  val instructionFetchAXI: AXI4ROIrrevocable =
    org.chipsalliance.amba.axi4.bundle.AXI4ROIrrevocable(parameter.instructionFetchParameter)
  val itimAXI: Option[AXI4RWIrrevocable] =
    parameter.itimParameter.map(p => Flipped(org.chipsalliance.amba.axi4.bundle.AXI4RWIrrevocable(p)))
}

@instantiable
class Frontend(val parameter: FrontendParameter)
    extends FixedIORawModule(new FrontendInterface(parameter))
    with SerializableModule[FrontendParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {
  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  def warpNum = parameter.warpNum
  def xLen = parameter.xLen
  def fetchWidth = parameter.fetchWidth
  def fetchBytes = parameter.fetchBytes
  def vaddrBitsExtended = parameter.vaddrBitsExtended
  def coreInstBits = parameter.coreInstBits
  def vaddrBits = parameter.vaddrBits
  def coreInstBytes = parameter.coreInstBytes
  def usingCompressed = parameter.usingCompressed
  def clock = io.clock

  object rocketParams {
    def clockGate = parameter.clockGate
  }

  object Instructions {
    def BEQ: BitPat = BitPat("b?????????????????000?????1100011")

    def JAL = BitPat("b?????????????????????????1101111")

    def JALR = BitPat("b?????????????????000?????1100111")

    def C_BEQZ = BitPat("b????????????????110???????????01")

    def C_BNEZ = BitPat("b????????????????111???????????01")

    def C_J = BitPat("b????????????????101???????????01")

    def C_ADD = BitPat("b????????????????1001??????????10")

    def C_MV = BitPat("b????????????????1000??????????10")
  }

  object Instructions32 {
    def C_JAL = BitPat("b????????????????001???????????01")
  }

  val clock_en_reg: Bool = Reg(Bool())
  val clock_en:     Bool = clock_en_reg || io.nonDiplomatic.cpu.might_request
  val gated_clock: Clock =
    if (!rocketParams.clockGate) clock
    else ClockGate(clock, clock_en)

  val icache = Instantiate(new ICache(parameter.icacheParameter))

  icache.io.clock := gated_clock
  icache.io.reset := io.reset
  icache.io.clock_enabled := clock_en
  (icache.io.itimAXI.zip(io.itimAXI)).foreach { case (frontend, itim) => itim :<>= frontend }
  io.instructionFetchAXI :<>= icache.io.instructionFetchAXI
  val tlb = Instantiate(new TLB(parameter.tlbParameter))
  tlb.io.clock := gated_clock
  tlb.io.reset := io.reset
  io.nonDiplomatic.ptw :<>= tlb.io.ptw
  io.nonDiplomatic.cpu.clock_enabled := clock_en
  val fq = Instantiate(new FetchQueue(parameter.fetchQueueParameter))
  fq.io.clock := io.clock
  fq.io.reset := io.reset.asBool || io.nonDiplomatic.cpu.req.valid

  assert(
    !(io.nonDiplomatic.cpu.req.valid || io.nonDiplomatic.cpu.sfence.valid || io.nonDiplomatic.cpu.flush_icache) || io.nonDiplomatic.cpu.might_request
  )

  withClock(gated_clock) { // entering gated-clock domain
    // 添加一个寄存器来跟踪请求是否已经被处理
    val req_processed = RegInit(false.B)
    // 添加一个寄存器来跟踪是否需要重放
    val need_replay = RegInit(false.B)
    val s1_valid = Reg(Bool())
    val s2_valid = RegInit(false.B)
    val s0_fq_has_space =
      !fq.io.mask(fq.io.mask.getWidth - 3) ||
        (!fq.io.mask(fq.io.mask.getWidth - 2) && (!s1_valid || !s2_valid)) ||
        (!fq.io.mask(fq.io.mask.getWidth - 1) && (!s1_valid && !s2_valid))

    val s0_valid = (io.nonDiplomatic.cpu.req.valid && !req_processed || need_replay) && s0_fq_has_space
    s1_valid := s0_valid
    val s1_pc = Reg(UInt(vaddrBitsExtended.W))
    val s1_wid = Reg(UInt(log2Ceil(warpNum).W))
    // val s1_speculative = Reg(Bool())
    // TODO: make it Const
    def alignPC(pc: UInt): UInt = ~(~pc | (coreInstBytes - 1).U)
    val s2_pc = RegInit(UInt(vaddrBitsExtended.W), alignPC(io.resetVector))
    val s2_wid = Reg(UInt(log2Ceil(warpNum).W))
    val s2_tlb_resp = Reg(tlb.io.resp.cloneType)
    val s2_xcpt = s2_tlb_resp.ae.inst || s2_tlb_resp.pf.inst
    // val s2_speculative = RegInit(false.B)
    val s2_partial_insn_valid = RegInit(false.B)
    val s2_partial_insn = Reg(UInt(coreInstBits.W))
    val wrong_path = RegInit(false.B)
    val s1_base_pc: UInt = ~(~s1_pc | (fetchBytes - 1).U)
    val ntpc = s1_base_pc + fetchBytes.U
    val predicted_npc = WireDefault(ntpc)
    val s2_replay = Wire(Bool())
    s2_replay := (s2_valid && !fq.io.enq.fire) || RegNext(s2_replay && !s0_valid, true.B)
    val npc = Mux(s2_replay, s2_pc, predicted_npc)

    s1_pc := io.nonDiplomatic.cpu.req.bits.pc
    s1_wid := io.nonDiplomatic.cpu.req.bits.wid

    io.nonDiplomatic.cpu.req.ready := !req_processed && !need_replay && s0_fq_has_space
    when(s0_valid) {
      need_replay := false.B
    }.elsewhen(s2_valid && !fq.io.enq.fire) {
      need_replay := true.B
    }

    when(s0_valid) {
      req_processed := true.B
    }.elsewhen(fq.io.enq.fire) {
      req_processed := false.B
    }
    // consider RVC fetches across blocks to be non-speculative if the first
    // part was non-speculative
    // val s0_speculative =
    //  if (usingCompressed) s1_speculative || s2_valid && !s2_speculative || predicted_taken
    //  else true.B
    // s1_speculative := false.B

    val s2_redirect = WireDefault(io.nonDiplomatic.cpu.req.valid)
    s2_valid := false.B
    when(!s2_replay) {
      s2_valid := !s2_redirect
      s2_pc := s1_pc
      s2_wid := s1_wid
      // s2_speculative := s1_speculative
      s2_tlb_resp := tlb.io.resp
    }

    val recent_progress_counter_init = 3.U
    val recent_progress_counter = RegInit(recent_progress_counter_init)
    val recent_progress = recent_progress_counter > 0.U
    when(io.nonDiplomatic.ptw.req.fire && recent_progress) { recent_progress_counter := recent_progress_counter - 1.U }
    when(io.nonDiplomatic.cpu.progress) { recent_progress_counter := recent_progress_counter_init }

    val s2_kill_speculative_tlb_refill = !recent_progress

    tlb.io.req.valid := s1_valid && !s2_replay
    def M_XRD = "b00000".U
    tlb.io.req.bits.cmd := M_XRD // Frontend only reads
    tlb.io.req.bits.vaddr := s1_pc
    tlb.io.req.bits.passthrough := false.B
    tlb.io.req.bits.size := log2Ceil(coreInstBytes * fetchWidth).U
    // tlb.io.req.bits.prv := io.nonDiplomatic.ptw.status.prv
    // tlb.io.req.bits.v := io.nonDiplomatic.ptw.status.v
    tlb.io.sfence := io.nonDiplomatic.cpu.sfence
    tlb.io.kill := !s2_valid || s2_kill_speculative_tlb_refill

    icache.io.req.valid := s0_valid
    // icache.io.req.bits.addr := io.nonDiplomatic.cpu.npc
    icache.io.req.bits.addr := io.nonDiplomatic.cpu.req.bits.pc
    icache.io.invalidate := io.nonDiplomatic.cpu.flush_icache
    icache.io.s1_paddr := tlb.io.resp.paddr
    icache.io.s2_vaddr := s2_pc
    icache.io.s1_kill := s2_redirect || tlb.io.resp.miss || s2_replay
    val s2_can_speculatively_refill =
      s2_tlb_resp.cacheable
    //       && !io.nonDiplomatic.ptw.customCSRs.asInstanceOf[RocketCustomCSRs].disableSpeculativeICacheRefill
    icache.io.s2_kill := !s2_can_speculatively_refill || s2_xcpt
    icache.io.s2_cacheable := s2_tlb_resp.cacheable
    icache.io.s2_prefetch := s2_tlb_resp.prefetchable
    //     && !io.ptw.customCSRs
    //       .asInstanceOf[RocketCustomCSRs]
    //       .disableICachePrefetch

    fq.io.enq.valid := s2_valid && (icache.io.resp.valid || (s2_kill_speculative_tlb_refill && s2_tlb_resp.miss) || (!s2_tlb_resp.miss && icache.io.s2_kill))
    fq.io.enq.bits.pc := s2_pc
    fq.io.enq.bits.wid := s2_wid
    // io.nonDiplomatic.cpu.npc := alignPC(Mux(io.nonDiplomatic.cpu.req.valid, io.nonDiplomatic.cpu.req.bits.pc, npc))
    io.nonDiplomatic.cpu.npc := alignPC(npc)

    fq.io.enq.bits.data := icache.io.resp.bits.data
    fq.io.enq.bits.mask := ((1 << fetchWidth) - 1).U << (if (log2Ceil(fetchWidth) == 0) 0.U
                                                         else
                                                           s2_pc(
                                                             log2Ceil(fetchWidth) + log2Ceil(coreInstBytes) - 1,
                                                             log2Ceil(coreInstBytes)
                                                           ))
    fq.io.enq.bits.xcpt.ae := s2_tlb_resp.ae.inst
    fq.io.enq.bits.xcpt.gf := false.B
    fq.io.enq.bits.xcpt.pf := s2_tlb_resp.pf.inst

    //     assert(
    //       !(s2_speculative && io.ptw.customCSRs
    //         .asInstanceOf[RocketCustomCSRs]
    //         .disableSpeculativeICacheRefill && !icache.io.s2_kill)
    //     )
    when(icache.io.resp.valid && icache.io.resp.bits.ae) { fq.io.enq.bits.xcpt.ae := true.B }

    io.nonDiplomatic.cpu.resp <> fq.io.deq

    // // supply guest physical address to commit stage
    // val gpa_valid = Reg(Bool())
    // val gpa = Reg(UInt(vaddrBitsExtended.W))
    // when(fq.io.enq.fire && s2_tlb_resp.gf.inst) {
    //   when(!gpa_valid) {
    //     gpa := s2_tlb_resp.gpa
    //   }
    //   gpa_valid := true.B
    // }
    // when(io.nonDiplomatic.cpu.req.valid) {
    //   gpa_valid := false.B
    // }
    // io.nonDiplomatic.cpu.gpa.valid := gpa_valid
    // io.nonDiplomatic.cpu.gpa.bits := gpa
    io.nonDiplomatic.cpu.gpa.valid := false.B
    io.nonDiplomatic.cpu.gpa.bits := 0.U

    // performance events
    io.nonDiplomatic.cpu.perf.acquire := icache.io.perf.acquire
    io.nonDiplomatic.cpu.perf.tlbMiss := io.nonDiplomatic.ptw.req.fire
    io.nonDiplomatic.errors := icache.io.errors

    // gate the clock
    clock_en_reg := !rocketParams.clockGate.B ||
      io.nonDiplomatic.cpu.might_request || // chicken bit
      icache.io.keep_clock_enabled || // I$ miss or ITIM access
      s1_valid || s2_valid || // some fetch in flight
      !tlb.io.req.ready || // handling TLB miss
      !fq.io.mask(fq.io.mask.getWidth - 1) // queue not full
  } // leaving gated-clock domain
}
