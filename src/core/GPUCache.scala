package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}
import chisel3.experimental.hierarchy.instantiable
import org.chipsalliance.tilelink.bundle._

/** GPU Cache parameter case class, similar to ICacheParameter. Contains all configuration for the cache structure.
  */
case class DCacheParameter(
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

object DCacheParameter {
  implicit def rwP: upickle.default.ReadWriter[DCacheParameter] = upickle.default.macroRW[DCacheParameter]
}

/** GPU Cache request bundle (from core to cache) vaddr: virtual address cmd: 0 = load, 1 = store size: access size
  * (log2) data: store data
  */
class DCacheReq(parameter: DCacheParameter) extends Bundle {
  val vaddr = UInt(parameter.vaddrBits.W)
  val cmd = UInt(2.W)
  val size = UInt(3.W)
  val data = UInt(parameter.dataWidth.W)
}

/** GPU Cache response bundle (from cache to core) vaddr: virtual address data: load data exception: miss or error
  */
class DCacheResp(parameter: DCacheParameter) extends Bundle {
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
    val pte = new PTE // Use standard PTE structure
    val valid = Bool()
  }))
}

/** PTW-specific memory request bundle */
class PTWMemoryReq(paddrBits: Int, dataWidth: Int) extends Bundle {
  val paddr = UInt(paddrBits.W)
  val cmd = UInt(2.W) // 0 = load
  val size = UInt(3.W) // 8 bytes for PTE
  val data = UInt(dataWidth.W) // Not used for loads
}

/** PTW-specific memory response bundle */
class PTWMemoryResp(paddrBits: Int, dataWidth: Int) extends Bundle {
  val paddr = UInt(paddrBits.W)
  val data = UInt(dataWidth.W)
  val exception = Bool()
}

/** Memory interface for GPU cache using TileLink
  */
class DMemoryIO(parameter: DCacheParameter) extends Bundle {
  val tilelink = new TLLink(
    TLLinkParameter(
      addressWidth = parameter.paddrBits,
      sourceWidth = 4,
      sinkWidth = 4,
      dataWidth = parameter.dataWidth,
      sizeWidth = 3,
      hasBCEChannels = false
    )
  )
}

/** GPU Cache top-level interface (IO bundle) Includes request/response, PTW, and memory interface
  */
class DCacheInterface(parameter: DCacheParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(if (parameter.useAsyncReset) AsyncReset() else Bool())
  val req = Flipped(Decoupled(new DCacheReq(parameter)))
  val resp = Decoupled(new DCacheResp(parameter))
  val ptw = new GPUTLBPTWIO(parameter.vaddrBits - parameter.pageOffsetBits, parameter.paddrBits)
  val memory = new DMemoryIO(parameter)
  val ptwMem = Flipped(Decoupled(new PTWMemoryReq(parameter.paddrBits, parameter.dataWidth)))
  val ptwMemResp = Decoupled(new PTWMemoryResp(parameter.paddrBits, parameter.dataWidth))
}

/** MSHR entry for miss tracking and merge waiting: which slots are waiting for this miss waitingData: data for each
  * waiting slot
  */
class DMSHREntry(parameter: DCacheParameter) extends Bundle {
  val valid = Bool()
  val vaddr = UInt(parameter.vaddrBits.W)
  val paddr = UInt(parameter.paddrBits.W)
  val cmd = UInt(2.W)
  val size = UInt(3.W)
  val data = UInt(parameter.dataWidth.W)
  val memoryReqSent = Bool() // Track if memory request has been sent
}

/** Simple fully-associative TLB for address translation Handles TLB miss by requesting PTW
  */
class DTLB(parameter: DCacheParameter) extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new DCacheReq(parameter)))
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
  val s_idle :: s_wait_ptw :: s_respond :: Nil = Enum(3)
  val state = RegInit(s_idle)
  val savedReq = Reg(new DCacheReq(parameter))
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
          // TLB命中，立即响应
          io.resp.valid := true.B
          io.resp.bits.paddr := Cat(pfn(hitEntry), io.req.bits.vaddr(parameter.pageOffsetBits - 1, 0))
          io.resp.bits.miss := false.B
          io.resp.bits.exception := false.B
        }.otherwise {
          // TLB miss: 保存请求，发送PTW请求
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
        // PTW响应到达，更新TLB
        val invalidVec = VecInit(valid.map(!_))
        val hasInvalid = invalidVec.asUInt.orR
        val replaceEntry = WireDefault(0.U(log2Ceil(nEntries).W))
        when(hasInvalid) {
          replaceEntry := PriorityEncoder(invalidVec)
        }.otherwise {
          replaceEntry := 0.U
        }
        valid(replaceEntry) := io.ptw.resp.bits.valid && io.ptw.resp.bits.pte.v
        vpn(replaceEntry) := savedVpn
        pfn(replaceEntry) := io.ptw.resp.bits.pte.ppn(pfnBits - 1, 0)

        // 进入响应状态，为原始请求提供最终结果
        state := s_respond
      }
    }
    is(s_respond) {
      // 为原始请求提供最终响应
      io.resp.valid := true.B
      io.resp.bits.paddr := Cat(
        pfn(PriorityEncoder(VecInit((0 until nEntries).map(i => valid(i) && vpn(i) === savedVpn)))),
        savedReq.vaddr(parameter.pageOffsetBits - 1, 0)
      )
      io.resp.bits.miss := false.B
      io.resp.bits.exception := !io.ptw.resp.bits.valid || !io.ptw.resp.bits.pte.v

      when(io.resp.ready) {
        state := s_idle
      }
    }
  }
}

/** GPU Cache main module Implements set-associative cache with TLB and MSHR support Interface and parameter style
  * matches ICache
  */
@instantiable
class DCache(val parameter: DCacheParameter)
    extends FixedIORawModule(new DCacheInterface(parameter))
    with SerializableModule[DCacheParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {
  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // TLB instance for address translation
  val tlb = Module(new DTLB(parameter))
  // MSHR array for miss tracking and merge
  val mshrs = RegInit(VecInit(Seq.fill(parameter.nMSHRs)(0.U.asTypeOf(new DMSHREntry(parameter)))))
  val mshrValid = RegInit(VecInit(Seq.fill(parameter.nMSHRs)(false.B)))
  // 1. 定义waiting请求Bundle
  class MSHRWaitingReq(parameter: DCacheParameter) extends Bundle {
    val vaddr = UInt(parameter.vaddrBits.W)
    val cmd = UInt(2.W)
    val size = UInt(3.W)
    val data = UInt(parameter.dataWidth.W)
  }
  // 2. 在DCache类中实例化SimpleFIFOQueue（先生成模块，再把io放到Vec里，支持UInt索引且所有端口都被驱动）
  val mshrWaitingQueuesSeq = Seq.tabulate(parameter.nMSHRs) { i =>
    Module(new SimpleFIFOQueue(new MSHRWaitingReq(parameter), 4)).io
  }
  val mshrWaitingQueues = VecInit(mshrWaitingQueuesSeq)
  // 默认赋值，防止端口未初始化
  for (i <- 0 until parameter.nMSHRs) {
    mshrWaitingQueues(i).enq.valid := false.B
    mshrWaitingQueues(i).enq.bits := 0.U.asTypeOf(new MSHRWaitingReq(parameter))
    mshrWaitingQueues(i).deq.ready := false.B
  }

  // 请求队列，用于处理资源不足的情况
  val reqQueue = Reg(Vec(8, new DCacheReq(parameter))) // 8个深度的请求队列
  val reqQueueValid = RegInit(VecInit(Seq.fill(8)(false.B)))
  val reqQueueHead = RegInit(0.U(3.W))
  val reqQueueTail = RegInit(0.U(3.W))
  val reqQueueEmpty = reqQueueHead === reqQueueTail && !reqQueueValid(reqQueueHead)
  val reqQueueFull = reqQueueHead === reqQueueTail && reqQueueValid(reqQueueHead)

  // Cache structure
  val indexBits = parameter.indexBits
  val offsetBits = parameter.offsetBits
  val tagBits = parameter.tagBits

  val tags = SyncReadMem(parameter.nSets, Vec(parameter.nWays, UInt(tagBits.W))) // Tag array
  val valids = RegInit(VecInit(Seq.fill(parameter.nSets)(VecInit(Seq.fill(parameter.nWays)(false.B)))))
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

  // === Default assignments ===
  io.resp.valid := false.B
  io.resp.bits.vaddr := 0.U
  io.resp.bits.data := 0.U
  io.resp.bits.exception := false.B
  io.memory.tilelink.a.valid := false.B
  io.memory.tilelink.a.bits := DontCare
  io.memory.tilelink.d.ready := true.B
  io.ptwMem.ready := false.B
  io.ptwMemResp.valid := false.B
  io.ptwMemResp.bits := DontCare

  // === TLB连接 ===
  tlb.io.req.valid := io.req.valid
  tlb.io.req.bits := io.req.bits
  tlb.io.ptw <> io.ptw

  // === 请求处理 ===
  // 1. TLB异常
  when(tlbValidReg && tlbRespReg.exception) {
    io.resp.valid := true.B
    io.resp.bits.vaddr := reqReg.vaddr
    io.resp.bits.data := 0.U
    io.resp.bits.exception := true.B
  }
    // 2. TLB正常，处理缓存访问
    .elsewhen(tlbValidReg && !tlbRespReg.exception) {
      val isLoad = reqReg.cmd === 0.U
      val isStore = reqReg.cmd === 1.U

      // 2.1 缓存命中
      when(hit) {
        // 对于load操作，返回缓存数据
        when(isLoad) {
          io.resp.valid := true.B
          io.resp.bits.vaddr := reqReg.vaddr
          io.resp.bits.data := dataVec(hitWay)
          io.resp.bits.exception := false.B
        }

        // 对于store操作，也需要响应确认
        when(isStore) {
          io.resp.valid := true.B
          io.resp.bits.vaddr := reqReg.vaddr
          io.resp.bits.data := 0.U // Store操作通常不需要返回数据
          io.resp.bits.exception := false.B
        }

        // store命中写回
        when(reqReg.cmd === 1.U) {
          val updatedDataVec = Wire(Vec(parameter.nWays, dataVec(0).cloneType))
          for (i <- 0 until parameter.nWays) {
            updatedDataVec(i) := Mux(i.U === hitWay, reqReg.data, dataVec(i))
          }
          dataArray.write(reqIdx, updatedDataVec)
        }
      }
        // 2.2 缓存未命中，但MSHR命中
        .elsewhen(mshrHit) {
          // 合并到已有MSHR，记录等待请求
          val idx = mshrHitIdx
          // 只负责enq，不要在主分支访问deq.bits
          val waitingReq = Wire(new MSHRWaitingReq(parameter))
          waitingReq.vaddr := reqReg.vaddr
          waitingReq.cmd := reqReg.cmd
          waitingReq.size := reqReg.size
          waitingReq.data := reqReg.data
          mshrWaitingQueues(idx).enq.valid := true.B
          mshrWaitingQueues(idx).enq.bits := waitingReq
          // 响应将在内存数据返回时通过响应状态机处理
        }
        // 2.3 缓存未命中，MSHR未命中，但有可用MSHR
        .elsewhen(mshrFree) {
          val idx = mshrFreeIdx
          mshrValid(idx) := true.B
          mshrs(idx).vaddr := reqReg.vaddr
          mshrs(idx).paddr := paddr
          mshrs(idx).cmd := reqReg.cmd
          mshrs(idx).size := reqReg.size
          mshrs(idx).data := reqReg.data
          mshrs(idx).memoryReqSent := false.B
          // 4. 新miss分配MSHR时，直接enq到队列
          val freeIdx = mshrFreeIdx
          val firstReq = Wire(new MSHRWaitingReq(parameter))
          firstReq.vaddr := reqReg.vaddr
          firstReq.cmd := reqReg.cmd
          firstReq.size := reqReg.size
          firstReq.data := reqReg.data
          mshrWaitingQueues(freeIdx).enq.valid := true.B
          mshrWaitingQueues(freeIdx).enq.bits := firstReq
          // 响应将在内存数据返回时通过响应状态机处理
        }
        // 2.4 缓存未命中，MSHR未命中，且MSHR满
        .otherwise {
          // MSHR满时，将请求加入队列等待处理
          when(!reqQueueFull) {
            reqQueue(reqQueueTail) := reqReg
            reqQueueValid(reqQueueTail) := true.B
            reqQueueTail := reqQueueTail + 1.U
          }.otherwise {
            // 队列也满了，返回异常
            io.resp.valid := true.B
            io.resp.bits.vaddr := reqReg.vaddr
            io.resp.bits.data := 0.U
            io.resp.bits.exception := true.B // 表示资源不足
          }
        }
    }

  // === PTW memory request handling ===
  // PTW memory requests have higher priority than normal cache requests
  val ptwMemReqValid = io.ptwMem.valid
  val ptwMemReq = io.ptwMem.bits

  // PTW memory request state machine
  val s_ptw_idle :: s_ptw_wait :: s_ptw_resp :: Nil = Enum(3)
  val ptwState = RegInit(s_ptw_idle)
  val ptwMemReqReg = Reg(new PTWMemoryReq(parameter.paddrBits, parameter.dataWidth))
  val ptwMemRespData = Reg(UInt(parameter.dataWidth.W))

  // PTW memory request processing
  when(ptwState === s_ptw_idle) {
    when(ptwMemReqValid) {
      ptwMemReqReg := ptwMemReq
      ptwState := s_ptw_wait
      io.ptwMem.ready := true.B
    }
  }

  when(ptwState === s_ptw_wait) {
    // Send memory request for PTW
    io.memory.tilelink.a.valid := true.B
    io.memory.tilelink.a.bits.opcode := OpCode.Get
    io.memory.tilelink.a.bits.param := Param.tieZero
    io.memory.tilelink.a.bits.size := ptwMemReqReg.size
    io.memory.tilelink.a.bits.source := (parameter.nMSHRs + 1).U // Use different source for PTW
    io.memory.tilelink.a.bits.address := ptwMemReqReg.paddr
    io.memory.tilelink.a.bits.mask := Fill(parameter.dataWidth / 8, true.B)
    io.memory.tilelink.a.bits.data := 0.U
    io.memory.tilelink.a.bits.corrupt := false.B

    when(io.memory.tilelink.d.fire) {
      ptwMemRespData := io.memory.tilelink.d.bits.data
      ptwState := s_ptw_resp
    }
  }

  when(ptwState === s_ptw_resp) {
    // Send response to PTW
    io.ptwMemResp.valid := true.B
    io.ptwMemResp.bits.paddr := ptwMemReqReg.paddr
    io.ptwMemResp.bits.data := ptwMemRespData
    io.ptwMemResp.bits.exception := false.B // Simplified for now

    when(io.ptwMemResp.ready) {
      ptwState := s_ptw_idle
    }
  }

  // === Normal cache memory request ===
  // 找到第一个可用的MSHR
  val mshrToMemoryOH = VecInit((0 until parameter.nMSHRs).map(i => mshrValid(i) && !mshrs(i).memoryReqSent))
  val mshrToMemory = PriorityEncoder(mshrToMemoryOH)
  val hasMSHRToMemory = mshrToMemoryOH.asUInt.orR

  // Normal cache memory requests only when PTW is not active
  val normalMemReqValid = hasMSHRToMemory && ptwState === s_ptw_idle
  io.memory.tilelink.a.valid := normalMemReqValid || (ptwState === s_ptw_wait)
  io.memory.tilelink.a.bits.opcode := Mux(
    ptwState === s_ptw_wait,
    OpCode.Get,
    Mux(mshrs(mshrToMemory).cmd === 0.U, OpCode.Get, OpCode.PutFullData)
  )
  io.memory.tilelink.a.bits.param := Param.tieZero
  io.memory.tilelink.a.bits.size := Mux(ptwState === s_ptw_wait, ptwMemReqReg.size, mshrs(mshrToMemory).size)
  io.memory.tilelink.a.bits.source := Mux(ptwState === s_ptw_wait, (parameter.nMSHRs + 1).U, mshrToMemory)
  io.memory.tilelink.a.bits.address := Mux(ptwState === s_ptw_wait, ptwMemReqReg.paddr, mshrs(mshrToMemory).paddr)
  io.memory.tilelink.a.bits.mask := Fill(parameter.dataWidth / 8, true.B)
  io.memory.tilelink.a.bits.data := Mux(ptwState === s_ptw_wait, 0.U, mshrs(mshrToMemory).data)
  io.memory.tilelink.a.bits.corrupt := false.B
  when(io.memory.tilelink.a.fire && !ptwState === s_ptw_wait) {
    mshrs(mshrToMemory).memoryReqSent := true.B
  }

  // === memory响应，依次响应所有等待请求 ===
  // 添加响应状态机
  val s_resp_idle :: s_resp_waiting :: Nil = Enum(2)
  val respState = RegInit(s_resp_idle)
  val respMshrIdx = Reg(UInt(log2Ceil(parameter.nMSHRs).W))
  val respData = Reg(UInt(parameter.dataWidth.W))
  val respVaddr = Reg(UInt(parameter.vaddrBits.W))

  when(io.memory.tilelink.d.fire) {
    // Check if this is a PTW response
    val isPTWResponse = io.memory.tilelink.d.bits.source === (parameter.nMSHRs + 1).U

    when(isPTWResponse) {
      // PTW response is handled in the PTW state machine above
      // This is just a safety check
    }.otherwise {
      val idx = mshrToMemory
      val mshr = mshrs(idx)
      val mshrPaddr = mshr.paddr
      val mshrIdx_cache = mshrPaddr(offsetBits + indexBits - 1, offsetBits)
      val mshrTag = mshrPaddr(parameter.paddrBits - 1, offsetBits + indexBits)
      val mshrTagVec = tags.read(mshrIdx_cache, true.B)
      val mshrValidVec = valids(mshrIdx_cache)
      val mshrDataVec = dataArray.read(mshrIdx_cache, true.B)
      // 选择替换路
      val way = WireDefault(0.U(log2Ceil(parameter.nWays).W))
      for (i <- 0 until parameter.nWays) {
        when(!mshrValidVec(i)) { way := i.U }
      }
      // 写入tag/data
      val updatedTagVec = Wire(Vec(parameter.nWays, mshrTagVec(0).cloneType))
      for (i <- 0 until parameter.nWays) {
        updatedTagVec(i) := Mux(i.U === way, mshrTag, mshrTagVec(i))
      }
      tags.write(mshrIdx_cache, updatedTagVec)
      valids(mshrIdx_cache)(way) := true.B
      val updatedDataVec = Wire(Vec(parameter.nWays, mshrDataVec(0).cloneType))
      for (i <- 0 until parameter.nWays) {
        updatedDataVec(i) := Mux(i.U === way, io.memory.tilelink.d.bits.data, mshrDataVec(i))
      }
      dataArray.write(mshrIdx_cache, updatedDataVec)

      // 启动响应状态机
      respState := s_resp_waiting
      respMshrIdx := idx
      respData := io.memory.tilelink.d.bits.data
      // 不直接访问deq.bits，respVaddr将在deq.valid时赋值
    }
  }

  // 响应状态机处理
  when(respState === s_resp_waiting) {
    val idx = respMshrIdx
    when(mshrWaitingQueues(idx).deq.valid) {
      val req = mshrWaitingQueues(idx).deq.bits
      val cmd = req.cmd
      val isLoad = cmd === 0.U
      val isStore = cmd === 1.U

      // 只在deq.valid时赋值respVaddr
      respVaddr := req.vaddr

      when(isLoad) {
        io.resp.valid := true.B
        io.resp.bits.vaddr := req.vaddr
        io.resp.bits.data := respData
        io.resp.bits.exception := false.B
      }.elsewhen(isStore) {
        io.resp.valid := true.B
        io.resp.bits.vaddr := req.vaddr
        io.resp.bits.data := 0.U
        io.resp.bits.exception := false.B
      }
      mshrWaitingQueues(idx).deq.ready := io.resp.ready
    }
    // 只要队列空了就idle
    when(!mshrWaitingQueues(idx).deq.valid) {
      respState := s_resp_idle
      mshrValid(idx) := false.B
      mshrs(idx).memoryReqSent := false.B
    }
  }

  // === ready信号 ===
  io.req.ready := tlb.io.req.ready && !reqValidReg && !reqQueueFull
  tlb.io.resp.ready := !tlbValidReg || !reqValidReg
  // TileLink D channel ready is set in default assignments

  // 请求队列处理
  // 当主流水线无法处理请求时，将其加入队列
  when(io.req.valid && !io.req.ready && !reqQueueFull) {
    reqQueue(reqQueueTail) := io.req.bits
    reqQueueValid(reqQueueTail) := true.B
    reqQueueTail := reqQueueTail + 1.U
  }

  // 从队列中取出请求进行处理
  val queueReqValid = !reqQueueEmpty && !reqValidReg
  val queueReq = reqQueue(reqQueueHead)

  when(queueReqValid) {
    // 将队列中的请求发送到TLB
    tlb.io.req.valid := true.B
    tlb.io.req.bits := queueReq
    when(tlb.io.req.fire) {
      reqQueueValid(reqQueueHead) := false.B
      reqQueueHead := reqQueueHead + 1.U
    }
  }
}
