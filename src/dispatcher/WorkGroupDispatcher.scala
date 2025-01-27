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
  val workgroup_task = Flipped(DecoupledIO(new WorkGroupTaskBundle))
  val warp_task = DecoupledIO(new WarpTaskBundle)
  val warp_task_resp = Flipped(DecoupledIO(new WarpTaskRespBundle))
  val workgroup_task_resp = DecoupledIO(new WorkGroupTaskRespBundle)
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

  io.workgroup_task.ready := state === Idle

  // Workgroup counters
  val workgroup_counter = RegInit(VecInit(Seq.fill(3)(0.U(16.W))))
  val workgroup_counter_x = workgroup_counter(0)
  val workgroup_counter_y = workgroup_counter(1)
  val workgroup_counter_z = workgroup_counter(2)

  val workgroup_size_x = currentTask.workgroup_size_x
  val workgroup_size_y = currentTask.workgroup_size_y
  val workgroup_size_z = currentTask.workgroup_size_z

  val workgroup_x_acc = (workgroup_counter_x =/= (workgroup_size_x - 1.U))
  val workgroup_y_acc =
    (workgroup_counter_x === (workgroup_size_x - 1.U)) & (workgroup_counter_y =/= (workgroup_size_y - 1.U))
  val workgroup_z_acc =
    (workgroup_counter_x === (workgroup_size_x - 1.U)) & (workgroup_counter_y === (workgroup_size_y - 1.U))

  val taskDone = workgroup_counter_x === (workgroup_size_x - 1.U) &&
    workgroup_counter_y === (workgroup_size_y - 1.U) &&
    workgroup_counter_z === (workgroup_size_z - 1.U)

  val s_rec_idle :: s_rec_working :: s_rec_finish :: Nil = Enum(3)
  val state_rec = RegInit(s_rec_idle)

  switch(state) {
    is(Idle) {
      when(io.workgroup_task.valid) {
        state := Dispatching
      }
    }
    is(Dispatching) {
      when(taskDone & io.warp_task.fire) {
        state := Finished
      }
    }
    is(Finished) {
      when(state_rec === s_rec_finish) {
        state := Idle
      }
    }
  }

  // state action
  switch(state) {
    is(Idle) {
      workgroup_counter_x := 0.U
      workgroup_counter_y := 0.U
      workgroup_counter_z := 0.U
      when(io.workgroup_task.fire) {
        currentTask := io.workgroup_task.bits
      }
    }
    is(Dispatching) {
      when(io.warp_task.fire) {
        when(workgroup_x_acc) {
          workgroup_counter_x := workgroup_counter_x + 1.U
        }.otherwise {
          workgroup_counter_x := 0.U
        }

        when(workgroup_y_acc) {
          workgroup_counter_y := workgroup_counter_y + 1.U
        }

        when(workgroup_z_acc) {
          workgroup_counter_z := workgroup_counter_z + 1.U
        }
      }
    }
  }

  io.warp_task.valid := state === Dispatching
  io.warp_task.bits.workgroup_size_x := currentTask.workgroup_size_x
  io.warp_task.bits.workgroup_size_y := currentTask.workgroup_size_y
  io.warp_task.bits.workgroup_size_z := currentTask.workgroup_size_z
  io.warp_task.bits.grid_size_x := currentTask.grid_size_x
  io.warp_task.bits.grid_size_y := currentTask.grid_size_y
  io.warp_task.bits.grid_size_z := currentTask.grid_size_z
  io.warp_task.bits.grid_id_x := currentTask.grid_id_x
  io.warp_task.bits.grid_id_y := currentTask.grid_id_y
  io.warp_task.bits.grid_id_z := currentTask.grid_id_z
  io.warp_task.bits.private_segment_size := currentTask.private_segment_size
  io.warp_task.bits.group_segment_size := currentTask.group_segment_size
  io.warp_task.bits.kernel_object := currentTask.kernel_object
  io.warp_task.bits.kernargs_address := currentTask.kernargs_address
  io.warp_task.bits.workgroup_id_x := workgroup_counter_x
  io.warp_task.bits.workgroup_id_y := workgroup_counter_y
  io.warp_task.bits.workgroup_id_z := workgroup_counter_z

  io.warp_task_resp.ready := state =/= Idle

  val workgroup_rcounter_x = RegInit(0.U(16.W))
  val workgroup_rcounter_y = RegInit(0.U(16.W))
  val workgroup_rcounter_z = RegInit(0.U(16.W))

  val workgroup_rcounter_x_acc = (workgroup_rcounter_x =/= (workgroup_size_x - 1.U))
  val workgroup_rcounter_y_acc =
    (workgroup_rcounter_x === (workgroup_size_x - 1.U)) & (workgroup_rcounter_y =/= (workgroup_size_y - 1.U))
  val workgroup_rcounter_z_acc =
    (workgroup_rcounter_x === (workgroup_size_x - 1.U)) & (workgroup_rcounter_y === (workgroup_size_y - 1.U))

  val recDone = workgroup_rcounter_x === (workgroup_size_x - 1.U) &&
    workgroup_rcounter_y === (workgroup_size_y - 1.U) &&
    workgroup_rcounter_z === (workgroup_size_z - 1.U)

  // rec state transition
  switch(state_rec) {
    is(s_rec_idle) {
      when(io.workgroup_task.fire) {
        state_rec := s_rec_working
      }
    }
    is(s_rec_working) {
      when(io.warp_task_resp.fire & recDone) {
        state_rec := s_rec_finish
      }
    }
    is(s_rec_finish) {
      state_rec := s_rec_idle
    }
  }

  // rec state action
  switch(state_rec) {
    is(s_rec_idle) {
      workgroup_rcounter_x := 0.U
      workgroup_rcounter_y := 0.U
      workgroup_rcounter_z := 0.U
    }
    is(s_rec_working) {
      when(io.warp_task_resp.fire) {
        when(workgroup_rcounter_x_acc) {
          workgroup_rcounter_x := workgroup_rcounter_x + 1.U
        }
        when(workgroup_rcounter_y_acc) {
          workgroup_rcounter_y := workgroup_rcounter_y + 1.U
        }
        when(workgroup_rcounter_z_acc) {
          workgroup_rcounter_z := workgroup_rcounter_z + 1.U
        }
      }
    }
  }

  io.workgroup_task_resp.valid := state_rec === s_rec_finish
  io.workgroup_task_resp.bits.finish := state_rec === s_rec_finish
}
