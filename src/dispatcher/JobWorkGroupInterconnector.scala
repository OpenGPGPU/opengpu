package ogpu.dispatcher

import chisel3._
import chisel3.util._
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}
import chisel3.experimental.hierarchy.instantiable

case class JobWorkGroupParameter(
  useAsyncReset: Boolean,
  clockGate:     Boolean,
  numJobPorts:   Int,
  numWGPorts:    Int)
    extends SerializableModuleParameter

class JobWorkGroupInterface(parameter: JobWorkGroupParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(if (parameter.useAsyncReset) AsyncReset() else Bool())
  val job = Vec(parameter.numJobPorts, Flipped(DecoupledIO(new WorkGroupTaskBundle)))
  val job_resp = Vec(parameter.numJobPorts, DecoupledIO(new WorkGroupTaskRespBundle))
  val wg = Vec(parameter.numWGPorts, DecoupledIO(new WorkGroupTaskBundle))
  val wg_resp = Vec(parameter.numWGPorts, Flipped(DecoupledIO(new WorkGroupTaskRespBundle)))
}

@instantiable
class JobWorkGroupInterconnector(val parameter: JobWorkGroupParameter)
    extends FixedIORawModule(new JobWorkGroupInterface(parameter))
    with SerializableModule[JobWorkGroupParameter]
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // Track workgroup port status
  val wgPortBusy = RegInit(VecInit(Seq.fill(parameter.numWGPorts)(false.B)))
  val wgPortToJob = RegInit(VecInit(Seq.fill(parameter.numWGPorts)(0.U(log2Ceil(parameter.numJobPorts).W))))

  // Round-robin arbiter for job selection
  val jobArbiter = Module(new RRArbiter(new WorkGroupTaskBundle, parameter.numJobPorts))

  // Connect job requests to arbiter - no jobActive restriction
  for (i <- 0 until parameter.numJobPorts) {
    jobArbiter.io.in(i).valid := io.job(i).valid
    jobArbiter.io.in(i).bits := io.job(i).bits
    io.job(i).ready := jobArbiter.io.in(i).ready
  }

  // Find free workgroup port
  val freeWGPorts = ~wgPortBusy.asUInt
  val freeWGPort = PriorityEncoder(freeWGPorts)
  val hasFreePorts = freeWGPorts.orR

  // Route selected job to free workgroup port
  jobArbiter.io.out.ready := hasFreePorts && io.wg(freeWGPort).ready
  for (i <- 0 until parameter.numWGPorts) {
    io.wg(i).bits := jobArbiter.io.out.bits
    io.wg(i).valid := i.U === freeWGPort && jobArbiter.io.out.fire
  }

  // Update status when request accepted
  when(jobArbiter.io.out.fire) {
    val wgIdx = freeWGPort
    val jobIdx = jobArbiter.io.chosen
    wgPortBusy(wgIdx) := true.B
    wgPortToJob(wgIdx) := jobIdx
  }

  // Default response ports to inactive
  io.wg_resp.foreach(_.ready := false.B)
  io.job_resp.foreach(_.valid := false.B)
  io.job_resp.foreach(_.bits := 0.U.asTypeOf(io.job_resp(0).bits))

  // Route responses back
  for (wgIdx <- 0 until parameter.numWGPorts) {
    when(wgPortBusy(wgIdx)) {
      val jobIdx = wgPortToJob(wgIdx)
      io.wg_resp(wgIdx).ready := io.job_resp(jobIdx).ready
      when(io.wg_resp(wgIdx).valid) {
        io.job_resp(jobIdx).valid := true.B
        io.job_resp(jobIdx).bits := io.wg_resp(wgIdx).bits
        when(io.wg_resp(wgIdx).fire) {
          wgPortBusy(wgIdx) := false.B
        }
      }
    }
  }
}
