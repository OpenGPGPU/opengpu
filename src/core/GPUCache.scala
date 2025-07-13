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
    val pte = new PTE // Use standard PTE structure
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
  val memoryReqSent = Bool() // Track if memory request has been sent
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
  val s_idle :: s_wait_ptw :: s_respond :: Nil = Enum(3)
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
  // 等待队列，记录每个MSHR等待的请求
  val mshrWaitVaddr = Reg(Vec(parameter.nMSHRs, Vec(4, UInt(parameter.vaddrBits.W))))
  val mshrWaitValid = Reg(Vec(parameter.nMSHRs, Vec(4, Bool())))
  val mshrWaitCmd = Reg(Vec(parameter.nMSHRs, Vec(4, UInt(2.W))))
  val mshrWaitSize = Reg(Vec(parameter.nMSHRs, Vec(4, UInt(3.W))))
  val mshrWaitData = Reg(Vec(parameter.nMSHRs, Vec(4, UInt(parameter.dataWidth.W))))

  // 请求队列，用于处理资源不足的情况
  val reqQueue = Reg(Vec(8, new GPUCacheReq(parameter))) // 8个深度的请求队列
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
  io.memory.req.valid := false.B
  io.memory.req.bits.addr := 0.U
  io.memory.req.bits.cmd := 0.U
  io.memory.req.bits.size := 0.U
  io.memory.req.bits.data := 0.U

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
          val slot = WireDefault(0.U(2.W))
          for (j <- 0 until 4) {
            when(!mshrWaitValid(idx)(j)) { slot := j.U }
          }
          mshrWaitVaddr(idx)(slot) := reqReg.vaddr
          mshrWaitCmd(idx)(slot) := reqReg.cmd
          mshrWaitSize(idx)(slot) := reqReg.size
          mshrWaitData(idx)(slot) := reqReg.data
          mshrWaitValid(idx)(slot) := true.B

          // MSHR命中时，请求会被合并，不需要立即响应
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
          // 第一个等待请求
          mshrWaitVaddr(idx)(0) := reqReg.vaddr
          mshrWaitCmd(idx)(0) := reqReg.cmd
          mshrWaitSize(idx)(0) := reqReg.size
          mshrWaitData(idx)(0) := reqReg.data
          mshrWaitValid(idx)(0) := true.B
          for (j <- 1 until 4) { mshrWaitValid(idx)(j) := false.B }

          // 新miss时，请求会被等待，不需要立即响应
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

  // === memory request ===
  // 找到第一个可用的MSHR
  val mshrToMemoryOH = VecInit((0 until parameter.nMSHRs).map(i => mshrValid(i) && !mshrs(i).memoryReqSent))
  val mshrToMemory = PriorityEncoder(mshrToMemoryOH)
  val hasMSHRToMemory = mshrToMemoryOH.asUInt.orR

  io.memory.req.valid := hasMSHRToMemory
  io.memory.req.bits.addr := mshrs(mshrToMemory).paddr
  io.memory.req.bits.cmd := mshrs(mshrToMemory).cmd
  io.memory.req.bits.size := mshrs(mshrToMemory).size
  io.memory.req.bits.data := mshrs(mshrToMemory).data
  when(io.memory.req.fire) {
    mshrs(mshrToMemory).memoryReqSent := true.B
  }

  // === memory响应，依次响应所有等待请求 ===
  // 添加响应状态机
  val s_resp_idle :: s_resp_waiting :: Nil = Enum(2)
  val respState = RegInit(s_resp_idle)
  val respMshrIdx = Reg(UInt(log2Ceil(parameter.nMSHRs).W))
  val respWaitingIdx = Reg(UInt(2.W))
  val respData = Reg(UInt(parameter.dataWidth.W))
  val respVaddr = Reg(UInt(parameter.vaddrBits.W))

  when(io.memory.resp.fire) {
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
      updatedDataVec(i) := Mux(i.U === way, io.memory.resp.bits.data, mshrDataVec(i))
    }
    dataArray.write(mshrIdx_cache, updatedDataVec)

    // 启动响应状态机
    respState := s_resp_waiting
    respMshrIdx := idx
    respWaitingIdx := 0.U
    respData := io.memory.resp.bits.data
    respVaddr := mshrWaitVaddr(idx)(0) // 第一个等待请求的虚拟地址
  }

  // 响应状态机处理
  when(respState === s_resp_waiting) {
    val idx = respMshrIdx
    val waitingIdx = respWaitingIdx

    // 检查当前等待槽是否有效
    when(mshrWaitValid(idx)(waitingIdx)) {
      val cmd = mshrWaitCmd(idx)(waitingIdx)
      val isLoad = cmd === 0.U
      val isStore = cmd === 1.U

      // 只有load操作需要返回数据，store操作只需要确认完成
      when(isLoad) {
        io.resp.valid := true.B
        io.resp.bits.vaddr := mshrWaitVaddr(idx)(waitingIdx)
        io.resp.bits.data := respData
        io.resp.bits.exception := false.B
      }.elsewhen(isStore) {
        // Store操作也需要响应，但数据字段可以为0或原始数据
        io.resp.valid := true.B
        io.resp.bits.vaddr := mshrWaitVaddr(idx)(waitingIdx)
        io.resp.bits.data := 0.U // Store操作通常不需要返回数据
        io.resp.bits.exception := false.B
      }

      // 清除当前等待槽
      mshrWaitValid(idx)(waitingIdx) := false.B

      // 移动到下一个等待槽
      when(io.resp.ready || (!isLoad && !isStore)) {
        respWaitingIdx := respWaitingIdx + 1.U
        when(respWaitingIdx < 3.U) {
          respVaddr := mshrWaitVaddr(idx)(respWaitingIdx + 1.U)
        }
      }
    }

    // 检查是否所有等待请求都已处理完
    val allProcessed = !mshrWaitValid(idx).asUInt.orR
    when(allProcessed) {
      respState := s_resp_idle
      mshrValid(idx) := false.B
      mshrs(idx).memoryReqSent := false.B
    }
  }

  // === ready信号 ===
  io.req.ready := tlb.io.req.ready && !reqValidReg && !reqQueueFull
  tlb.io.resp.ready := !tlbValidReg || !reqValidReg
  io.memory.resp.ready := true.B

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
