package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.SerializableModule

/** LSU (Load/Store Unit)
  *
  * Minimal LSU that wraps DCache and provides a Decoupled request/response interface carrying warp id. This LSU
  * currently supports at most one outstanding request to keep the mapping between DCache response and warp id trivial.
  *
  * Integration notes:
  *   - Connect io.memory and io.ptw/ptwMem/ptwMemResp to the SoC fabric and PTW.
  *   - The execution pipeline should feed LSU requests and consume LSU responses.
  */

class LSUReq(parameter: OGPUParameter) extends Bundle {
  val wid = UInt(log2Ceil(parameter.warpNum).W)
  val vaddr = UInt(parameter.vaddrBitsExtended.W)
  val cmd = UInt(2.W) // 0 = load, 1 = store
  val size = UInt(3.W)
  val data = UInt(parameter.xLen.W) // store data
  val rd = UInt(5.W) // destination register for loads
}

class LSUResp(parameter: OGPUParameter) extends Bundle {
  val wid = UInt(log2Ceil(parameter.warpNum).W)
  val vaddr = UInt(parameter.vaddrBitsExtended.W)
  val data = UInt(parameter.xLen.W)
  val exception = Bool()
  val rd = UInt(5.W)
}

class LSUInterface(parameter: OGPUParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())

  val req = Flipped(Decoupled(new LSUReq(parameter)))
  val resp = Decoupled(new LSUResp(parameter))

  // External DCache request/response (shared DCache instantiated elsewhere)
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

@instantiable
class LSU(val parameter: OGPUParameter)
    extends FixedIORawModule(new LSUInterface(parameter))
    with SerializableModule[OGPUParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // Request gating to support single outstanding request
  val busy = RegInit(false.B)
  val savedWid = Reg(UInt(log2Ceil(parameter.warpNum).W))
  val savedVaddr = Reg(UInt(parameter.vaddrBitsExtended.W))
  val savedRd = Reg(UInt(5.W))

  // Default outputs
  io.req.ready := io.dcacheReq.ready && !busy
  io.resp.valid := false.B
  io.resp.bits.wid := 0.U
  io.resp.bits.vaddr := 0.U
  io.resp.bits.data := 0.U
  io.resp.bits.exception := false.B
  io.resp.bits.rd := 0.U

  // Drive DCache request
  io.dcacheReq.valid := io.req.valid && !busy
  io.dcacheReq.bits.vaddr := io.req.bits.vaddr
  io.dcacheReq.bits.cmd := io.req.bits.cmd
  io.dcacheReq.bits.size := io.req.bits.size
  io.dcacheReq.bits.data := io.req.bits.data
  io.dcacheReq.bits // keep referenced

  when(io.req.fire) {
    busy := true.B
    savedWid := io.req.bits.wid
    savedVaddr := io.req.bits.vaddr
    savedRd := io.req.bits.rd
  }

  // DCache response -> LSU response
  when(io.dcacheResp.valid) {
    io.resp.valid := true.B
    io.resp.bits.wid := savedWid
    io.resp.bits.vaddr := io.dcacheResp.bits.vaddr
    io.resp.bits.data := io.dcacheResp.bits.data
    io.resp.bits.exception := io.dcacheResp.bits.exception
    io.resp.bits.rd := savedRd
  }

  // Clear busy when response is consumed
  when(io.resp.fire) {
    busy := false.B
  }

  // Backpressure DCache response according to LSU resp consumer
  io.dcacheResp.ready := io.resp.ready
}
