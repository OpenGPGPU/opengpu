package ogpu.dispatcher

import chisel3._
import chisel3.util._
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}
import chisel3.experimental.hierarchy.instantiable

case class WorkGroupDispatcherParameter(
  useAsyncReset: Boolean,
  clockGate:     Boolean,
  warpSize:      Int)
    extends SerializableModuleParameter

class WorkGroupDispatcherInterface(parameter: WorkGroupDispatcherParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(if (parameter.useAsyncReset) AsyncReset() else Bool())
  val task = Flipped(DecoupledIO(new WorkGroupTaskBundle))
  val warp_task = DecoupledIO(new WarpTaskBundle)
  val task_resp = Flipped(DecoupledIO(new WorkGroupTaskRespBundle))
}

@instantiable
class WorkGroupDispatcher(val parameter: WorkGroupDispatcherParameter)
    extends FixedIORawModule(new WorkGroupDispatcherInterface(parameter))
    with SerializableModule[WorkGroupDispatcherParameter]
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  object State extends ChiselEnum {
    val Idle, Dispatching, Finished = Value
  }
  import State._

  val state = RegInit(Idle)
  val currentTask = RegInit(0.U.asTypeOf(new WorkGroupTaskBundle()))

  when(state === Idle && io.task.valid) {
    currentTask := io.task.bits
    state := Dispatching
  }

  when(state === Dispatching) {
    // Logic to break down workgroup into warp tasks
    // This is a placeholder for actual warp task generation logic
    io.warp_task.bits := generateWarpTask(currentTask)
    io.warp_task.valid := true.B

    // Transition to Finished state after dispatching
    state := Finished
  }

  when(state === Finished) {
    io.task_resp.valid := true.B
    io.task_resp.bits := createTaskResponse(currentTask)
    state := Idle
  }

  def generateWarpTask(task: WorkGroupTaskBundle): WarpTaskBundle = {
    // Implement logic to create a warp task from the workgroup task
    new WarpTaskBundle() // Placeholder
  }

  def createTaskResponse(task: WorkGroupTaskBundle): WorkGroupTaskRespBundle = {
    // Implement logic to create a response for the dispatched task
    new WorkGroupTaskRespBundle() // Placeholder
  }
}
