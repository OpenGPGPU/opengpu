package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.SerializableModule
import chisel3.experimental.hierarchy.instantiable

class WarpFrontendBundle(parameter: OGPUParameter) extends Bundle {
  // Interface with WarpScheduler
  val warp_start = Flipped(DecoupledIO(new Bundle {
    val wid = UInt(log2Ceil(parameter.warpNum).W)
    val pc = UInt(parameter.vaddrBitsExtended.W)
  }))
  val reg_init_done = Input(Bool())

  // Interface with Frontend using standard types
  val frontend_req = DecoupledIO(
    new FrontendReq(
      parameter.warpNum,
      parameter.vaddrBitsExtended
    )
  )
  val frontend_resp = Flipped(
    DecoupledIO(
      new FrontendResp(
        parameter.warpNum,
        parameter.vaddrBits,
        parameter.vaddrBitsExtended,
        parameter.coreInstBits,
        parameter.fetchWidth
      )
    )
  )

  // Interface with Decoder
  val decode = DecoupledIO(
    new InstructionBundle(
      parameter.warpNum,
      parameter.coreInstBits,
      parameter.vaddrBitsExtended
    )
  )

  // Branch resolution interface
  val branch_update = Flipped(ValidIO(new BranchResultBundle(parameter)))

  // Add warp finish signal
  val warp_finish = Input(Valid(UInt(log2Ceil(parameter.warpNum).W)))

  // New decoder control interface
  val decode_control = Flipped(ValidIO(new Bundle {
    val wid = UInt(log2Ceil(parameter.warpNum).W)
    val is_branch = Bool() // Branch instruction
    val next_pc = UInt(parameter.vaddrBitsExtended.W) // Next PC to fetch
    val activate = Bool() // Whether to activate warp
    val is_compressed = Bool() // Whether current instruction is compressed
  }))
}

class WarpFrontendInterface(parameter: OGPUParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(if (parameter.useAsyncReset) AsyncReset() else Bool())

  // Interface with WarpScheduler
  val warp_start = Flipped(DecoupledIO(new Bundle {
    val wid = UInt(log2Ceil(parameter.warpNum).W)
    val pc = UInt(parameter.vaddrBitsExtended.W)
  }))
  val reg_init_done = Input(Bool())

  // Interface with Frontend using standard types
  val frontend_req = DecoupledIO(
    new FrontendReq(
      parameter.warpNum,
      parameter.vaddrBitsExtended
    )
  )
  val frontend_resp = Flipped(
    DecoupledIO(
      new FrontendResp(
        parameter.warpNum,
        parameter.vaddrBits,
        parameter.vaddrBitsExtended,
        parameter.coreInstBits,
        parameter.fetchWidth
      )
    )
  )

  // Interface with Decoder
  val decode = DecoupledIO(
    new InstructionBundle(
      parameter.warpNum,
      parameter.coreInstBits,
      parameter.vaddrBitsExtended
    )
  )

  // Branch resolution interface
  val branch_update = Flipped(ValidIO(new BranchResultBundle(parameter)))

  // Add warp finish signal
  val warp_finish = Input(Valid(UInt(log2Ceil(parameter.warpNum).W)))

  // New decoder control interface
  val decode_control = Flipped(ValidIO(new Bundle {
    val wid = UInt(log2Ceil(parameter.warpNum).W)
    val is_branch = Bool() // Branch instruction
    val next_pc = UInt(parameter.vaddrBitsExtended.W) // Next PC to fetch
    val activate = Bool() // Whether to activate warp
    val is_compressed = Bool() // Whether current instruction is compressed
  }))
}

@instantiable
class WarpFrontend(val parameter: OGPUParameter)
    extends FixedIORawModule(new WarpFrontendInterface(parameter))
    with SerializableModule[OGPUParameter]
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // State registers
  val warpActive = RegInit(VecInit(Seq.fill(parameter.warpNum)(false.B)))
  val warpBlocked = RegInit(VecInit(Seq.fill(parameter.warpNum)(false.B)))
  val warpPC = Reg(Vec(parameter.warpNum, UInt(parameter.vaddrBitsExtended.W)))

  // Default values - simplify ready signal
  io.warp_start.ready := !warpActive.reduce(_ && _) // True if not all warps are active

  // Round-robin arbiter for warp selection
  val warpArbiter = Module(
    new RRArbiter(
      new Bundle {
        val wid = UInt(log2Ceil(parameter.warpNum).W)
      },
      parameter.warpNum
    )
  )

  // Warp scheduling
  for (i <- 0 until parameter.warpNum) {
    warpArbiter.io.in(i).valid := warpActive(i) && !warpBlocked(i)
    warpArbiter.io.in(i).bits.wid := i.U
  }

  // Connect arbiter's ready signal
  warpArbiter.io.out.ready := io.frontend_req.ready

  val selectedWarp = warpArbiter.io.chosen
  val validWarpSelected = warpArbiter.io.out.valid

  // Per-warp instruction buffers
  val instBuffers = Seq.tabulate(parameter.warpNum) { _ =>
    Module(
      new Queue(
        new Bundle {
          val inst = UInt(parameter.coreInstBits.W)
          val pc = UInt(parameter.vaddrBitsExtended.W)
        },
        parameter.fetchBufferSize
      )
    )
  }

  // Default values
  io.frontend_req.valid := false.B
  io.frontend_req.bits.pc := 0.U
  io.frontend_req.bits.wid := 0.U
  io.decode.valid := false.B
  io.frontend_resp.ready := false.B // Add default value
  instBuffers.foreach(_.io.enq.valid := false.B)
  instBuffers.foreach(_.io.enq.bits.pc := io.frontend_resp.bits.pc)
  instBuffers.foreach(_.io.enq.bits.inst := io.frontend_resp.bits.data(parameter.coreInstBits - 1, 0))
  instBuffers.foreach(_.io.deq.ready := false.B)
  io.decode.bits.instruction := 0.U
  io.decode.bits.pc := 0.U
  io.decode.bits.wid := 0.U

  // Warp activation and deactivation
  when(io.warp_start.valid) {
    val startWarpOH = UIntToOH(io.warp_start.bits.wid)
    for (i <- 0 until parameter.warpNum) {
      when(startWarpOH(i)) {
        warpActive(i) := true.B
        warpBlocked(i) := false.B
        warpPC(i) := io.warp_start.bits.pc
      }
    }
  }

  when(io.warp_finish.valid) {
    val finishWarpOH = UIntToOH(io.warp_finish.bits)
    for (i <- 0 until parameter.warpNum) {
      when(finishWarpOH(i)) {
        warpActive(i) := false.B
        warpBlocked(i) := false.B
      }
    }
  }

  // Fetch control
  when(validWarpSelected && !warpBlocked(selectedWarp)) {
    // Request instruction fetch
    io.frontend_req.valid := true.B
    io.frontend_req.bits.pc := warpPC(selectedWarp)
    io.frontend_req.bits.wid := selectedWarp
    // No longer update PC here, wait for decoder feedback
  }

  when(io.frontend_req.fire) {
    warpBlocked(selectedWarp) := true.B
  }

  // Handle instruction buffer for each warp
  when(io.frontend_resp.valid) {
    val wid = io.frontend_resp.bits.wid
    val widOH = UIntToOH(wid)

    // Set ready based on target buffer's availability
    io.frontend_resp.ready := Mux1H(widOH, instBuffers.map(_.io.enq.ready))

    // Connect to all buffers
    for (i <- 0 until parameter.warpNum) {
      instBuffers(i).io.enq.valid := widOH(i) && io.frontend_resp.valid
    }
  }

  // Add buffer arbiter
  val bufferArbiter = Module(
    new RRArbiter(
      new InstructionBundle(
        parameter.warpNum,
        parameter.coreInstBits,
        parameter.vaddrBitsExtended
      ),
      parameter.warpNum
    )
  )

  // Connect buffer outputs to arbiter inputs
  for (i <- 0 until parameter.warpNum) {
    bufferArbiter.io.in(i).valid := instBuffers(i).io.deq.valid &&
      warpActive(i)
    bufferArbiter.io.in(i).bits.instruction := instBuffers(i).io.deq.bits.inst
    bufferArbiter.io.in(i).bits.pc := instBuffers(i).io.deq.bits.pc
    bufferArbiter.io.in(i).bits.wid := i.U

    // Connect dequeue ready signals
    instBuffers(i).io.deq.ready := bufferArbiter.io.in(i).ready &&
      bufferArbiter.io.in(i).valid
  }

  // Connect arbiter to decode interface
  bufferArbiter.io.out.ready := io.decode.ready
  io.decode.valid := bufferArbiter.io.out.valid
  io.decode.bits.instruction := bufferArbiter.io.out.bits.instruction
  io.decode.bits.pc := bufferArbiter.io.out.bits.pc
  io.decode.bits.wid := bufferArbiter.io.out.bits.wid

  // Handle decoder control response
  when(io.decode_control.valid) {
    val wid = io.decode_control.bits.wid
    val widOH = UIntToOH(wid)
    for (i <- 0 until parameter.warpNum) {
      when(widOH(i)) {
        when(io.decode_control.bits.activate) {
          warpBlocked(i) := false.B
          warpPC(i) := Mux(
            io.decode_control.bits.is_compressed,
            io.decode_control.bits.next_pc + 2.U,
            io.decode_control.bits.next_pc + 4.U
          )
        }.elsewhen(io.decode_control.bits.is_branch) {
          warpBlocked(i) := true.B
        }
      }
    }
  }

  // Branch resolution
  when(io.branch_update.valid) {
    val branchWarpOH = UIntToOH(io.branch_update.bits.wid)
    for (i <- 0 until parameter.warpNum) {
      when(branchWarpOH(i)) {
        warpBlocked(i) := false.B
        warpPC(i) := io.branch_update.bits.target
      }
    }
  }
}
