package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}
import chisel3.experimental.hierarchy.instantiable

/** GPU Cache parameter case class, similar to ICacheParameter. Contains all configuration for the cache structure.
  */
case class GPUCacheParameter(
  useAsyncReset: Boolean = false, // Whether to use async reset
  nSets:         Int, // Number of sets
  nWays:         Int, // Number of ways (associativity)
  blockBytes:    Int, // Cache block size in bytes
  vaddrBits:     Int, // Virtual address width
  paddrBits:     Int, // Physical address width
  dataWidth:     Int, // Data width (in bits)
  nMSHRs:        Int = 4, // Number of MSHR entries
  nTLBEntries:   Int = 64, // Number of TLB entries
  pageBytes:     Int = 4096 // Page size in bytes (default 4KB)
) extends SerializableModuleParameter {
  val indexBits = log2Ceil(nSets)
  val offsetBits = log2Ceil(blockBytes)
  val tagBits = paddrBits - indexBits - offsetBits
  val pageOffsetBits = log2Ceil(pageBytes)
}

object GPUCacheParameter {
  implicit def rwP: upickle.default.ReadWriter[GPUCacheParameter] = upickle.default.macroRW[GPUCacheParameter]
}

/** GPU Cache request bundle (from core to cache) vaddr: virtual address cmd: 0 = load, 1 = store size: access size
  * (log2) data: store data
  */
class GPUCacheReq(parameter: GPUCacheParameter) extends Bundle {
  val vaddr = UInt(parameter.vaddrBits.W)
  val cmd = UInt(2.W)
  val size = UInt(3.W)
  val data = UInt(parameter.dataWidth.W)
}

/** GPU Cache response bundle (from cache to core) vaddr: virtual address data: load data exception: miss or error
  */
class GPUCacheResp(parameter: GPUCacheParameter) extends Bundle {
  val vaddr = UInt(parameter.vaddrBits.W)
  val data = UInt(parameter.dataWidth.W)
  val exception = Bool()
}

/** Page Table Walker interface for TLB miss handling vpn: virtual page number pte: page table entry (physical page
  * number)
  */
class GPUTLBPTWIO(val vpnBits: Int, val paddrBits: Int) extends Bundle {
  val req = Decoupled(new Bundle {
    val vpn = UInt(vpnBits.W)
  })
  val resp = Flipped(Decoupled(new Bundle {
    val pte = UInt(paddrBits.W)
    val valid = Bool()
  }))
}

/** Memory request bundle (from cache to memory system)
  */
class GPUMemoryReq(parameter: GPUCacheParameter) extends Bundle {
  val addr = UInt(parameter.paddrBits.W)
  val cmd = UInt(2.W)
  val size = UInt(3.W)
  val data = UInt(parameter.dataWidth.W)
}

/** Memory response bundle (from memory system to cache)
  */
class GPUMemoryResp(parameter: GPUCacheParameter) extends Bundle {
  val addr = UInt(parameter.paddrBits.W)
  val data = UInt(parameter.dataWidth.W)
  val valid = Bool()
}

/** Memory interface for GPU cache
  */
class GPUMemoryIO(parameter: GPUCacheParameter) extends Bundle {
  val req = Decoupled(new GPUMemoryReq(parameter))
  val resp = Flipped(Decoupled(new GPUMemoryResp(parameter)))
}

/** GPU Cache top-level interface (IO bundle) Includes request/response, PTW, and memory interface
  */
class GPUCacheInterface(parameter: GPUCacheParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(if (parameter.useAsyncReset) AsyncReset() else Bool())
  val req = Flipped(Decoupled(new GPUCacheReq(parameter)))
  val resp = Decoupled(new GPUCacheResp(parameter))
  val ptw = new GPUTLBPTWIO(parameter.vaddrBits - parameter.pageOffsetBits, parameter.paddrBits)
  val memory = new GPUMemoryIO(parameter)
}

/** MSHR entry for miss tracking and merge waiting: which slots are waiting for this miss waitingData: data for each
  * waiting slot
  */
class GPUMSHREntry(parameter: GPUCacheParameter) extends Bundle {
  val valid = Bool()
  val vaddr = UInt(parameter.vaddrBits.W)
  val paddr = UInt(parameter.paddrBits.W)
  val cmd = UInt(2.W)
  val size = UInt(3.W)
  val data = UInt(parameter.dataWidth.W)
  val waiting = Vec(4, Bool())
  val waitingData = Vec(4, UInt(parameter.dataWidth.W))
}

/** Simple fully-associative TLB for address translation Handles TLB miss by requesting PTW
  */
class GPUTLB(parameter: GPUCacheParameter) extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new GPUCacheReq(parameter)))
    val resp = Decoupled(new Bundle {
      val paddr = UInt(parameter.paddrBits.W)
      val miss = Bool()
      val exception = Bool()
    })
    val ptw = new GPUTLBPTWIO(parameter.vaddrBits - parameter.pageOffsetBits, parameter.paddrBits)
  })
  val vpnBits = parameter.vaddrBits - parameter.pageOffsetBits
  val pfnBits = parameter.paddrBits - parameter.pageOffsetBits
  val nEntries = parameter.nTLBEntries
  val valid = RegInit(VecInit(Seq.fill(nEntries)(false.B)))
  val vpn = Reg(Vec(nEntries, UInt(vpnBits.W)))
  val pfn = Reg(Vec(nEntries, UInt(pfnBits.W)))

  // 状态机
  val s_idle :: s_wait_ptw :: Nil = Enum(2)
  val state = RegInit(s_idle)
  val savedReq = Reg(new GPUCacheReq(parameter))
  val savedVpn = Reg(UInt(vpnBits.W))

  // PTW请求
  val ptwReqValidReg = RegInit(false.B)
  val ptwReqVpnReg = Reg(UInt(vpnBits.W))
  io.ptw.req.valid := ptwReqValidReg
  io.ptw.req.bits.vpn := ptwReqVpnReg

  // 默认
  io.req.ready := (state === s_idle)
  io.resp.valid := false.B
  io.resp.bits.paddr := 0.U
  io.resp.bits.miss := false.B
  io.resp.bits.exception := false.B
  io.ptw.resp.ready := (state === s_wait_ptw)

  val reqVpn = io.req.bits.vaddr(parameter.vaddrBits - 1, parameter.pageOffsetBits)
  val hitVec = VecInit((0 until nEntries).map(i => valid(i) && vpn(i) === reqVpn))
  val hit = hitVec.asUInt.orR
  val hitEntry = WireDefault(0.U(log2Ceil(nEntries).W))
  for (i <- 0 until nEntries) { when(hitVec(i)) { hitEntry := i.U } }

  switch(state) {
    is(s_idle) {
      ptwReqValidReg := false.B
      when(io.req.valid) {
        when(hit) {
          io.resp.valid := true.B
          io.resp.bits.paddr := Cat(pfn(hitEntry), io.req.bits.vaddr(parameter.pageOffsetBits - 1, 0))
          io.resp.bits.miss := false.B
          io.resp.bits.exception := false.B
        }.otherwise {
          // miss: 发PTW请求，保存请求
          savedReq := io.req.bits
          savedVpn := reqVpn
          ptwReqValidReg := true.B
          ptwReqVpnReg := reqVpn
          state := s_wait_ptw
        }
      }
    }
    is(s_wait_ptw) {
      ptwReqValidReg := false.B
      when(io.ptw.resp.valid) {
        // 写TLB（简单替换策略：第一个无效，否则替换0号）
        val invalidVec = VecInit(valid.map(!_))
        val hasInvalid = invalidVec.asUInt.orR
        val replaceEntry = WireDefault(0.U(log2Ceil(nEntries).W))
        when(hasInvalid) {
          replaceEntry := PriorityEncoder(invalidVec)
        }.otherwise {
          replaceEntry := 0.U
        }
        valid(replaceEntry) := io.ptw.resp.bits.valid
        vpn(replaceEntry) := savedVpn
        pfn(replaceEntry) := io.ptw.resp.bits.pte(pfnBits - 1, 0)
        // 返回resp
        io.resp.valid := true.B
        io.resp.bits.paddr := Cat(io.ptw.resp.bits.pte(pfnBits - 1, 0), savedReq.vaddr(parameter.pageOffsetBits - 1, 0))
        io.resp.bits.miss := false.B
        io.resp.bits.exception := !io.ptw.resp.bits.valid
        when(io.resp.ready) {
          state := s_idle
        }
      }
    }
  }
}

/** GPU Cache main module Implements set-associative cache with TLB and MSHR support Interface and parameter style
  * matches ICache
  */
@instantiable
class GPUCache(val parameter: GPUCacheParameter)
    extends FixedIORawModule(new GPUCacheInterface(parameter))
    with SerializableModule[GPUCacheParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {
  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // TLB instance for address translation
  val tlb = Module(new GPUTLB(parameter))
  // MSHR array for miss tracking and merge
  val mshrs = RegInit(VecInit(Seq.fill(parameter.nMSHRs)(0.U.asTypeOf(new GPUMSHREntry(parameter)))))
  val mshrValid = RegInit(VecInit(Seq.fill(parameter.nMSHRs)(false.B)))

  // Cache structure
  val indexBits = parameter.indexBits
  val offsetBits = parameter.offsetBits
  val tagBits = parameter.tagBits

  val tags = SyncReadMem(parameter.nSets, Vec(parameter.nWays, UInt(tagBits.W))) // Tag array
  val valids = RegInit(VecInit(Seq.fill(parameter.nSets)(VecInit(Seq.fill(parameter.nWays)(false.B))))) // Valid bits
  val dataArray = SyncReadMem(parameter.nSets, Vec(parameter.nWays, UInt(parameter.dataWidth.W))) // Data array

  // Pipeline registers for request and TLB response
  val reqReg = RegEnable(io.req.bits, io.req.fire)
  val tlbRespReg = RegEnable(tlb.io.resp.bits, tlb.io.resp.fire)
  val reqValidReg = RegNext(io.req.fire, false.B)
  val tlbValidReg = RegNext(tlb.io.resp.fire, false.B)

  // Address translation
  val paddr = tlbRespReg.paddr
  val reqIdx = paddr(offsetBits + indexBits - 1, offsetBits)
  val reqTag = paddr(parameter.paddrBits - 1, offsetBits + indexBits)

  // Tag and data lookup
  val tagVec = tags.read(reqIdx, tlb.io.resp.fire)
  val validVec = valids(reqIdx)
  val hitVec = VecInit((0 until parameter.nWays).map(i => validVec(i) && tagVec(i) === reqTag))
  val hit = hitVec.asUInt.orR
  val hitWay = WireDefault(0.U(log2Ceil(parameter.nWays).W))
  for (i <- 0 until parameter.nWays) { when(hitVec(i)) { hitWay := i.U } }
  val dataVec = dataArray.read(reqIdx, tlb.io.resp.fire)

  // MSHR lookup and allocation
  val mshrHitVec = VecInit(
    (0 until parameter.nMSHRs).map(i =>
      mshrValid(i) && mshrs(i).paddr(parameter.paddrBits - 1, offsetBits) === paddr(parameter.paddrBits - 1, offsetBits)
    )
  )
  val mshrHit = mshrHitVec.asUInt.orR
  val mshrHitIdx = WireDefault(0.U(log2Ceil(parameter.nMSHRs).W))
  for (i <- 0 until parameter.nMSHRs) { when(mshrHitVec(i)) { mshrHitIdx := i.U } }
  val mshrFreeVec = VecInit((0 until parameter.nMSHRs).map(i => !mshrValid(i)))
  val mshrFree = mshrFreeVec.asUInt.orR
  val mshrFreeIdx = WireDefault(0.U(log2Ceil(parameter.nMSHRs).W))
  for (i <- 0 until parameter.nMSHRs) { when(mshrFreeVec(i)) { mshrFreeIdx := i.U } }

  // Allocate new MSHR on miss
  when(tlbValidReg && !hit && !mshrHit && mshrFree) {
    mshrValid(mshrFreeIdx) := true.B
    mshrs(mshrFreeIdx).vaddr := reqReg.vaddr
    mshrs(mshrFreeIdx).paddr := paddr
    mshrs(mshrFreeIdx).cmd := reqReg.cmd
    mshrs(mshrFreeIdx).size := reqReg.size
    mshrs(mshrFreeIdx).data := reqReg.data
    mshrs(mshrFreeIdx).waiting(0) := true.B
    mshrs(mshrFreeIdx).waitingData(0) := reqReg.data
  }
  // Merge request to existing MSHR
  when(tlbValidReg && !hit && mshrHit) {
    val waitingIdx = PriorityEncoder(mshrs(mshrHitIdx).waiting.map(!_))
    mshrs(mshrHitIdx).waiting(waitingIdx) := true.B
    mshrs(mshrHitIdx).waitingData(waitingIdx) := reqReg.data
  }

  // Select an MSHR to send memory request
  val mshrToMemory = WireDefault(0.U(log2Ceil(parameter.nMSHRs).W))
  // Use priority encoder to select the first valid MSHR
  for (i <- 0 until parameter.nMSHRs) {
    when(mshrValid(i)) {
      mshrToMemory := i.U
    }
  }
  io.memory.req.valid := mshrValid(mshrToMemory)
  io.memory.req.bits.addr := mshrs(mshrToMemory).paddr
  io.memory.req.bits.cmd := mshrs(mshrToMemory).cmd
  io.memory.req.bits.size := mshrs(mshrToMemory).size
  io.memory.req.bits.data := mshrs(mshrToMemory).data

  // On memory response, update cache and clear MSHR
  when(io.memory.resp.fire) {
    val mshrIdx = mshrToMemory
    val mshr = mshrs(mshrIdx)
    val mshrPaddr = mshr.paddr
    val mshrIdx_cache = mshrPaddr(offsetBits + indexBits - 1, offsetBits)
    val mshrTag = mshrPaddr(parameter.paddrBits - 1, offsetBits + indexBits)

    // Read current cache state for this index
    val mshrTagVec = tags.read(mshrIdx_cache, true.B)
    val mshrValidVec = valids(mshrIdx_cache)
    val mshrDataVec = dataArray.read(mshrIdx_cache, true.B)

    // Find a way to allocate (simple LRU replacement)
    val way = WireDefault(0.U(log2Ceil(parameter.nWays).W))
    for (i <- 0 until parameter.nWays) {
      when(!mshrValidVec(i)) {
        way := i.U
      }
    }

    // Update cache
    val updatedTagVec = Wire(Vec(parameter.nWays, mshrTagVec(0).cloneType))
    for (i <- 0 until parameter.nWays) {
      updatedTagVec(i) := Mux(i.U === way, mshrTag, mshrTagVec(i))
    }
    tags.write(mshrIdx_cache, updatedTagVec)
    valids(mshrIdx_cache)(way) := true.B
    val updatedDataVec = Wire(Vec(parameter.nWays, mshrDataVec(0).cloneType))
    for (i <- 0 until parameter.nWays) {
      updatedDataVec(i) := Mux(i.U === way, io.memory.resp.bits.data, mshrDataVec(i))
    }
    dataArray.write(mshrIdx_cache, updatedDataVec)

    // Clear waiting bits and MSHR
    for (i <- 0 until 4) {
      when(mshr.waiting(i)) {
        mshrs(mshrIdx).waiting(i) := false.B
      }
    }
    mshrValid(mshrIdx) := false.B
  }

  // Connect TLB
  tlb.io.req.valid := io.req.valid
  tlb.io.req.bits := io.req.bits
  tlb.io.ptw <> io.ptw

  // Response logic: hit or MSHR hit can respond
  val canRespond = tlbValidReg && (hit || mshrHit)
  io.resp.valid := canRespond
  io.resp.bits.vaddr := reqReg.vaddr
  io.resp.bits.data := Mux(hit, dataVec(hitWay), 0.U)
  io.resp.bits.exception := tlbRespReg.miss || (!hit && !mshrHit && tlbValidReg && (reqReg.cmd === 0.U))

  // Write operation (on hit)
  when(tlbValidReg && reqReg.cmd === 1.U && hit) {
    val way = hitWay
    val updatedTagVec = Wire(Vec(parameter.nWays, tagVec(0).cloneType))
    for (i <- 0 until parameter.nWays) {
      updatedTagVec(i) := Mux(i.U === way, reqTag, tagVec(i))
    }
    tags.write(reqIdx, updatedTagVec)
    valids(reqIdx)(way) := true.B
    val updatedDataVec = Wire(Vec(parameter.nWays, dataVec(0).cloneType))
    for (i <- 0 until parameter.nWays) {
      updatedDataVec(i) := Mux(i.U === way, reqReg.data, dataVec(i))
    }
    dataArray.write(reqIdx, updatedDataVec)
  }

  // Ready signal: only accept new request if pipeline and MSHR are free
  val canAccept = !reqValidReg && mshrFree
  io.req.ready := tlb.io.req.ready && canAccept
  tlb.io.resp.ready := !tlbValidReg || canRespond
  io.memory.resp.ready := true.B
}
