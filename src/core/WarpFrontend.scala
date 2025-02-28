package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}
import chisel3.experimental.hierarchy.instantiable

case class WarpFrontendParameter(
  useAsyncReset:     Boolean,
  clockGate:         Boolean,
  warpNum:           Int,
  vaddrBits:         Int,
  vaddrBitsExtended: Int,
  entries:           Int,
  coreInstBits:      Int,
  fetchWidth:        Int,
  fetchBufferSize:   Int)
    extends SerializableModuleParameter

class WarpFrontendInterface(parameter: WarpFrontendParameter) extends Bundle {
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
  val decode = DecoupledIO(new Bundle {
    val inst = UInt(parameter.coreInstBits.W)
    val pc = UInt(parameter.vaddrBitsExtended.W)
    val wid = UInt(log2Ceil(parameter.warpNum).W)
  })
  val decode_branch = Input(Bool())

  // Branch resolution interface
  val branch_update = Flipped(ValidIO(new Bundle {
    val wid = UInt(log2Ceil(parameter.warpNum).W)
    val pc = UInt(parameter.vaddrBitsExtended.W)
    val target = UInt(parameter.vaddrBitsExtended.W)
  }))

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
class WarpFrontend(val parameter: WarpFrontendParameter)
    extends FixedIORawModule(new WarpFrontendInterface(parameter))
    with SerializableModule[WarpFrontendParameter]
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  object State extends ChiselEnum {
    val Idle, WaitInit, Fetching, WaitBranch = Value
  }
  import State._

  // State registers
  val state = RegInit(Idle)
  val warpActive = RegInit(VecInit(Seq.fill(parameter.warpNum)(false.B)))
  val warpBlocked = RegInit(VecInit(Seq.fill(parameter.warpNum)(false.B)))
  val warpPC = Reg(Vec(parameter.warpNum, UInt(parameter.vaddrBitsExtended.W)))
  val currentWarp = Reg(UInt(log2Ceil(parameter.warpNum).W))

  // Control signals
  val fetchValid = RegInit(false.B)

  // Default values
  io.warp_start.ready := state === Idle
  io.frontend_req.valid := false.B
  io.frontend_req.bits.pc := 0.U
  io.frontend_req.bits.wid := 0.U
  io.decode.valid := false.B

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
  warpArbiter.io.out.ready := true.B // Always ready to accept arbiter's selection

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

  // Control signals
  val branchPending = RegInit(VecInit(Seq.fill(parameter.warpNum)(false.B)))

  // Default values
  io.warp_start.ready := true.B
  io.frontend_req.valid := false.B
  io.decode.valid := false.B
  io.frontend_resp.ready := false.B // Add default value
  instBuffers.foreach(_.io.enq.valid := false.B)
  instBuffers.foreach(_.io.enq.bits.pc := io.frontend_resp.bits.pc)
  instBuffers.foreach(_.io.enq.bits.inst := io.frontend_resp.bits.data(parameter.coreInstBits - 1, 0))
  instBuffers.foreach(_.io.deq.ready := false.B)
  io.decode.bits.inst := 0.U
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
  when(validWarpSelected && !branchPending(selectedWarp)) {
    // Request instruction fetch
    io.frontend_req.valid := true.B
    io.frontend_req.bits.pc := warpPC(selectedWarp)
    io.frontend_req.bits.wid := selectedWarp

    // No longer update PC here, wait for decoder feedback
  }

  // Handle instruction buffer for each warp
  when(io.frontend_resp.valid) {
    val wid = io.frontend_resp.bits.wid
    val widOH = UIntToOH(wid)

    // Set ready based on target buffer's availability
    io.frontend_resp.ready := Mux1H(widOH, instBuffers.map(!_.io.enq.ready))

    // Connect to all buffers
    for (i <- 0 until parameter.warpNum) {
      instBuffers(i).io.enq.valid := widOH(i) && io.frontend_resp.valid
    }
  }

  // Issue instruction from selected warp
  when(validWarpSelected && io.decode.ready) {
    // Create one-hot encoding for selectedWarp
    val selectedWarpOH = UIntToOH(selectedWarp)

    // Valid and data signals for all buffers
    val bufferValids = VecInit(instBuffers.map(_.io.deq.valid))
    val bufferInsts = VecInit(instBuffers.map(_.io.deq.bits.inst))
    val bufferPCs = VecInit(instBuffers.map(_.io.deq.bits.pc))

    // Select buffer outputs using Mux1H
    when(Mux1H(selectedWarpOH, bufferValids)) {
      io.decode.valid := true.B
      io.decode.bits.inst := Mux1H(selectedWarpOH, bufferInsts)
      io.decode.bits.pc := Mux1H(selectedWarpOH, bufferPCs)
      io.decode.bits.wid := selectedWarp

      // Set ready for selected buffer
      for (i <- 0 until parameter.warpNum) {
        instBuffers(i).io.deq.ready := selectedWarpOH(i)
      }
    }
  }

  // Branch handling
  when(io.decode_branch) {
    val branchWarpOH = UIntToOH(selectedWarp)
    for (i <- 0 until parameter.warpNum) {
      when(branchWarpOH(i)) {
        branchPending(i) := true.B
      }
    }
  }

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
        branchPending(i) := false.B
        warpBlocked(i) := false.B
        when(io.branch_update.bits.pc =/= warpPC(i)) {
          instBuffers(i).reset := true.B
          warpPC(i) := io.branch_update.bits.target
        }
      }
    }
  }
}
