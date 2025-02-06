package ogpu.dispatcher

import chisel3._
import chisel3.util._
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}
import chisel3.experimental.hierarchy.instantiable

case class WorkgroupCUParameter(
  useAsyncReset: Boolean,
  clockGate:     Boolean,
  numWGPorts:    Int,
  numCUPorts:    Int)
    extends SerializableModuleParameter

class WorkgroupCUInterface(parameter: WorkgroupCUParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(if (parameter.useAsyncReset) AsyncReset() else Bool())
  val wg = Vec(parameter.numWGPorts, Flipped(DecoupledIO(new WarpTaskBundle)))
  val wg_resp = Vec(parameter.numWGPorts, DecoupledIO(new WarpTaskRespBundle))
  val cu = Vec(parameter.numCUPorts, DecoupledIO(new WarpTaskBundle))
  val cu_resp = Vec(parameter.numCUPorts, Flipped(DecoupledIO(new WarpTaskRespBundle)))
}

@instantiable
class WorkgroupCUInterconnector(val parameter: WorkgroupCUParameter)
    extends FixedIORawModule(new WorkgroupCUInterface(parameter))
    with SerializableModule[WorkgroupCUParameter]
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // Track CU port assignments and busy status
  val cuPortBusy = RegInit(VecInit(Seq.fill(parameter.numCUPorts)(false.B)))
  val wgToCU = RegInit(VecInit(Seq.fill(parameter.numWGPorts)(0.U(log2Ceil(parameter.numCUPorts).W))))
  val wgAssigned = RegInit(VecInit(Seq.fill(parameter.numWGPorts)(false.B)))
  val wgActive = RegInit(VecInit(Seq.fill(parameter.numWGPorts)(false.B)))

  // Round-robin arbiter for workgroup selection
  val wgArbiter = Module(new RRArbiter(new WarpTaskBundle, parameter.numWGPorts))

  // Connect workgroup requests to arbiter
  for (i <- 0 until parameter.numWGPorts) {
    val isAssigned = wgAssigned(i)
    val assignedCU = wgToCU(i)
    val canSend = !isAssigned || (isAssigned && !cuPortBusy(assignedCU))

    wgArbiter.io.in(i).valid := io.wg(i).valid && canSend
    wgArbiter.io.in(i).bits := io.wg(i).bits
    io.wg(i).ready := wgArbiter.io.in(i).ready && canSend
  }

  // Find free CU port or use assigned one
  val freeCUPorts = ~cuPortBusy.asUInt
  val chosenWG = wgArbiter.io.chosen
  val assignedCU = wgToCU(chosenWG)
  val needNewCU = !wgAssigned(chosenWG) && wgArbiter.io.out.bits.first_warp
  val freeCUPort = PriorityEncoder(freeCUPorts)
  val selectedCU = Mux(needNewCU, freeCUPort, assignedCU)
  val hasFreeCU = freeCUPorts.orR || !needNewCU

  // Route selected workgroup to CU port
  wgArbiter.io.out.ready := hasFreeCU && io.cu(selectedCU).ready
  for (i <- 0 until parameter.numCUPorts) {
    io.cu(i).bits := wgArbiter.io.out.bits
    io.cu(i).valid := i.U === selectedCU && wgArbiter.io.out.fire
  }

  // Update status when request accepted
  when(wgArbiter.io.out.fire) {
    val cuIdx = selectedCU
    val wgIdx = wgArbiter.io.chosen
    cuPortBusy(cuIdx) := true.B

    // Set assignment on first warp
    when(wgArbiter.io.out.bits.first_warp) {
      wgToCU(wgIdx) := cuIdx
      wgAssigned(wgIdx) := true.B
      wgActive(wgIdx) := true.B
    }

    // Clear active status on last warp
    when(wgArbiter.io.out.bits.last_warp) {
      wgActive(wgIdx) := false.B
    }
  }

  // Route responses back
  for (cuIdx <- 0 until parameter.numCUPorts) {
    when(cuPortBusy(cuIdx)) {
      val wgIdx =
        VecInit((0 until parameter.numWGPorts).map(i => wgAssigned(i) && wgToCU(i) === cuIdx.U)).indexWhere(x => x)
      io.cu_resp(cuIdx).ready := io.wg_resp(wgIdx).ready
      when(io.cu_resp(cuIdx).valid) {
        io.wg_resp(wgIdx).valid := true.B
        io.wg_resp(wgIdx).bits := io.cu_resp(cuIdx).bits

        when(io.cu_resp(cuIdx).fire && !wgActive(wgIdx)) {
          cuPortBusy(cuIdx) := false.B
          wgAssigned(wgIdx) := false.B // Clear assignment after response
        }
      }
    }
  }
}
