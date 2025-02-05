package ogpu.dispatcher

import chisel3._
import chisel3.util._
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}
import chisel3.experimental.hierarchy.instantiable

case class QueueJobParameter(
  useAsyncReset: Boolean,
  clockGate:     Boolean,
  numQueuePorts: Int,
  numJobPorts:   Int)
    extends SerializableModuleParameter

class QueueJobInterface(parameter: QueueJobParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(if (parameter.useAsyncReset) AsyncReset() else Bool())
  val queue = Vec(parameter.numQueuePorts, Flipped(DecoupledIO(new QueueBundle)))
  val queue_resp = Vec(parameter.numQueuePorts, DecoupledIO(new QueueRespBundle))
  val job = Vec(parameter.numJobPorts, DecoupledIO(new QueueBundle))
  val job_resp = Vec(parameter.numJobPorts, Flipped(DecoupledIO(new QueueRespBundle)))
}

@instantiable
class QueueJobInterconnector(val parameter: QueueJobParameter)
    extends FixedIORawModule(new QueueJobInterface(parameter))
    with SerializableModule[QueueJobParameter]
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // Track which queues are currently active
  val queueActive = RegInit(VecInit(Seq.fill(parameter.numQueuePorts)(false.B)))

  // Job port status - indicates if a port is busy
  val jobPortBusy = RegInit(VecInit(Seq.fill(parameter.numJobPorts)(false.B)))

  // Track which Queue port is connected to which job port
  val jobPortToQueue = RegInit(VecInit(Seq.fill(parameter.numJobPorts)(0.U(log2Ceil(parameter.numQueuePorts).W))))

  // Round-robin arbiter for Queue port selection
  val queueArbiter = Module(new RRArbiter(new QueueBundle, parameter.numQueuePorts))

  // Connect Queue requests to arbiter - only allow if queue not active
  for (i <- 0 until parameter.numQueuePorts) {
    queueArbiter.io.in(i).valid := io.queue(i).valid && !queueActive(i)
    queueArbiter.io.in(i).bits := io.queue(i).bits
    io.queue(i).ready := queueArbiter.io.in(i).ready && !queueActive(i)
  }

  // Find first free job port using priority encoder
  val freeJobPorts = ~jobPortBusy.asUInt
  val freeJobPort = PriorityEncoder(freeJobPorts)
  val hasFreePorts = freeJobPorts.orR

  // Route selected Queue request to free job port
  queueArbiter.io.out.ready := hasFreePorts && io.job(freeJobPort).ready
  for (i <- 0 until parameter.numJobPorts) {
    io.job(i).bits := queueArbiter.io.out.bits
    io.job(i).valid := i.U === freeJobPort && queueArbiter.io.out.fire
  }

  // Update status when request accepted
  when(queueArbiter.io.out.fire) {
    val jobIdx = freeJobPort
    val queueIdx = queueArbiter.io.chosen
    jobPortBusy(jobIdx) := true.B
    jobPortToQueue(jobIdx) := queueIdx
    queueActive(queueIdx) := true.B
  }

  // Default all ports to inactive
  io.job_resp.foreach(_.ready := false.B)
  io.queue_resp.foreach(_.valid := false.B)
  io.queue_resp.foreach(_.bits := 0.U.asTypeOf(io.queue_resp(0).bits))

  // Route responses back and clear active status
  for (jobIdx <- 0 until parameter.numJobPorts) {
    when(jobPortBusy(jobIdx)) {
      val queueIdx = jobPortToQueue(jobIdx)
      io.job_resp(jobIdx).ready := io.queue_resp(queueIdx).ready
      when(io.job_resp(jobIdx).valid) {
        io.queue_resp(queueIdx).valid := true.B
        io.queue_resp(queueIdx).bits := io.job_resp(jobIdx).bits
        when(io.job_resp(jobIdx).fire) {
          jobPortBusy(jobIdx) := false.B
          queueActive(queueIdx) := false.B // Clear active status when response complete
        }
      }
    }
  }

  // Optional clock gating
}
