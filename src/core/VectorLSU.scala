package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.SerializableModule

/** Vector LSU request: describes a vector memory operation over multiple elements. */
class VecLSUReq(parameter: OGPUParameter) extends Bundle {
  val wid = UInt(log2Ceil(parameter.warpNum).W)
  val base = UInt(parameter.vaddrBitsExtended.W)
  val stride = SInt(parameter.xLen.W) // stride in bytes per element
  val elemCount = UInt(16.W) // number of elements to access
  val size = UInt(3.W) // log2(bytes) per element
  val isStore = Bool()
  val rd = UInt(5.W) // destination scalar register for loads (placeholder)
}

/** Vector LSU per-element response. */
class VecLSUResp(parameter: OGPUParameter) extends Bundle {
  val wid = UInt(log2Ceil(parameter.warpNum).W)
  val index = UInt(16.W)
  val data = UInt(parameter.xLen.W)
  val exception = Bool()
  val rd = UInt(5.W)
}

class VectorLSUInterface(parameter: OGPUParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())

  // High-level vector request/response
  val req = Flipped(Decoupled(new VecLSUReq(parameter)))
  // For stores, provide a stream of store data (one per element)
  val storeData = Flipped(Decoupled(UInt(parameter.xLen.W)))
  // For loads, return element-wise data
  val resp = Decoupled(new VecLSUResp(parameter))

  // External DCache request/response (shared cache instantiated elsewhere)
  private val dcp = DCacheParameter(
    useAsyncReset = parameter.useAsyncReset,
    nSets = 64,
    nWays = 4,
    blockBytes = 64,
    vaddrBits = parameter.vaddrBitsExtended,
    paddrBits = 40,
    dataWidth = parameter.xLen
  )
  val dcacheReq = Decoupled(new DCacheReq(dcp))
  val dcacheResp = Flipped(Decoupled(new DCacheResp(dcp)))
}

/** VectorLSU
  *
  * Minimal multi-element LSU that can issue a sequence of cache requests for a single vector memory operation (one
  * outstanding at a time, multiple requests total). Suitable for early integration and can be extended to support
  * multiple outstanding beats in future.
  */
@instantiable
class VectorLSU(val parameter: OGPUParameter)
    extends FixedIORawModule(new VectorLSUInterface(parameter))
    with SerializableModule[OGPUParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // State
  val busy = RegInit(false.B)
  val curWid = Reg(UInt(log2Ceil(parameter.warpNum).W))
  val curBase = Reg(UInt(parameter.vaddrBitsExtended.W))
  val curStride = Reg(SInt(parameter.xLen.W))
  val curElems = Reg(UInt(16.W))
  val curSize = Reg(UInt(3.W))
  val curIsStore = Reg(Bool())
  val curRd = Reg(UInt(5.W))
  val elemIdx = Reg(UInt(16.W))
  val waitingResp = RegInit(false.B)

  // Defaults
  io.req.ready := !busy
  io.storeData.ready := false.B
  io.resp.valid := false.B
  io.resp.bits.wid := 0.U
  io.resp.bits.index := 0.U
  io.resp.bits.data := 0.U
  io.resp.bits.exception := false.B
  io.resp.bits.rd := 0.U

  io.dcacheReq.valid := false.B
  io.dcacheReq.bits.vaddr := 0.U
  io.dcacheReq.bits.cmd := 0.U
  io.dcacheReq.bits.size := 0.U
  io.dcacheReq.bits.data := 0.U
  io.dcacheResp.ready := false.B

  // Address computation for current element
  val offsetBytes = (elemIdx.zext * curStride).asSInt
  val elemAddr = (curBase.zext + offsetBytes).asUInt

  // Accept a new vector request
  when(io.req.fire) {
    busy := true.B
    curWid := io.req.bits.wid
    curBase := io.req.bits.base
    curStride := io.req.bits.stride
    curElems := io.req.bits.elemCount
    curSize := io.req.bits.size
    curIsStore := io.req.bits.isStore
    curRd := io.req.bits.rd
    elemIdx := 0.U
    waitingResp := false.B
  }

  // Issue a DCache request when not waiting for a response
  val haveMore = elemIdx < curElems
  val canIssue = busy && haveMore && !waitingResp
  when(canIssue) {
    io.dcacheReq.valid := true.B
    io.dcacheReq.bits.vaddr := elemAddr
    io.dcacheReq.bits.cmd := Mux(curIsStore, 1.U, 0.U)
    io.dcacheReq.bits.size := curSize
    // For store, consume one storeData beat
    when(curIsStore) {
      io.storeData.ready := io.dcacheReq.ready
      io.dcacheReq.bits.data := io.storeData.bits
    }
  }

  when(canIssue && io.dcacheReq.fire) {
    waitingResp := true.B
  }

  // Handle DCache response
  io.dcacheResp.ready := busy && waitingResp && io.resp.ready
  when(io.dcacheResp.fire) {
    io.resp.valid := !curIsStore // only loads return data; stores could also ack per element
    io.resp.bits.wid := curWid
    io.resp.bits.index := elemIdx
    io.resp.bits.data := io.dcacheResp.bits.data
    io.resp.bits.exception := io.dcacheResp.bits.exception
    io.resp.bits.rd := curRd

    waitingResp := false.B
    elemIdx := elemIdx + 1.U
  }

  // Finish when all elements processed (for stores, finish on response beats as well)
  when(busy && (elemIdx === curElems) && !waitingResp) {
    busy := false.B
  }
}
