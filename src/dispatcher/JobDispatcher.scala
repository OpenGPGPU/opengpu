package ogpu.dispatcher

import chisel3._
import chisel3.util._
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}
import chisel3.experimental.hierarchy.instantiable

case class DispatcherParameter(
  useAsyncReset: Boolean,
  clockGate:     Boolean,
  bufferNum:     Int)
    extends SerializableModuleParameter

class DispatcherBundle(parameter: DispatcherParameter) extends Bundle {
  val queue = Flipped(DecoupledIO(new QueueBundle))
  val task = DecoupledIO(new WorkGroupTaskBundle)
  val task_resp = Flipped(DecoupledIO(new WorkGroupTaskRespBundle))
}

class DispatcherInterface(parameter: DispatcherParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(if (parameter.useAsyncReset) AsyncReset() else Bool())
  val queue = Flipped(DecoupledIO(new QueueBundle))
  val queue_resp = DecoupledIO(new QueueRespBundle) // 添加队列响应输出
  val task = DecoupledIO(new WorkGroupTaskBundle)
  val task_resp = Flipped(DecoupledIO(new WorkGroupTaskRespBundle))
}

@instantiable
class JobDispatcher(val parameter: DispatcherParameter)
    extends FixedIORawModule(new DispatcherInterface(parameter))
    with SerializableModule[DispatcherParameter]
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  object State extends ChiselEnum {
    val Idle, Working, Finish = Value
  }
  import State._

  val state = RegInit(Idle)
  val queue = RegInit(0.U.asTypeOf(new QueueBundle()))

  val grid_x = queue.grid_size_x
  val grid_y = queue.grid_size_y
  val grid_z = queue.grid_size_z

  // Grid counters
  val grid_counter = RegInit(VecInit(Seq.fill(3)(0.U(32.W))))
  val grid_counter_x = grid_counter(0)
  val grid_counter_y = grid_counter(1)
  val grid_counter_z = grid_counter(2)

  io.queue.ready := state === Idle

  val taskDone = grid_counter_x === (grid_x - 1.U) &&
    grid_counter_y === (grid_y - 1.U) &&
    grid_counter_z === (grid_z - 1.U)

  val grid_x_acc = (grid_counter_x =/= (grid_x - 1.U))
  val grid_y_acc = (grid_counter_x === (grid_x - 1.U)) & (grid_counter_y =/= (grid_y - 1.U))
  val grid_z_acc = (grid_counter_x === (grid_x - 1.U)) & (grid_counter_y === (grid_y - 1.U))

  val grid_rcounter_x = RegInit(0.U(32.W))
  val grid_rcounter_y = RegInit(0.U(32.W))
  val grid_rcounter_z = RegInit(0.U(32.W))

  val grid_x_racc = (grid_rcounter_x =/= (grid_x - 1.U))
  val grid_y_racc = (grid_rcounter_x === (grid_x - 1.U)) & (grid_rcounter_y =/= (grid_y - 1.U))
  val grid_z_racc = (grid_rcounter_x === (grid_x - 1.U)) & (grid_rcounter_y === (grid_y - 1.U))

  val recDone = grid_rcounter_x === (grid_x - 1.U) &
    grid_rcounter_y === (grid_y - 1.U) &
    grid_rcounter_z === (grid_z - 1.U)

  val s_rec_idle :: s_rec_working :: s_rec_finish :: Nil = Enum(3)
  val state_rec = RegInit(s_rec_idle)

  // state transition
  switch(state) {
    is(Idle) {
      when(io.queue.fire) {
        state := Working
      }
    }
    is(Working) {
      when(taskDone & io.task.fire) {
        state := Finish
      }
    }
    is(Finish) {
      when(state_rec === s_rec_finish) {
        state := Idle
      }
    }
  }

  io.task.valid := state === Working
  io.task.bits.workgroup_size_x := queue.workgroup_size_x
  io.task.bits.workgroup_size_y := queue.workgroup_size_y
  io.task.bits.workgroup_size_z := queue.workgroup_size_z
  io.task.bits.grid_size_x := queue.grid_size_x
  io.task.bits.grid_size_y := queue.grid_size_y
  io.task.bits.grid_size_z := queue.grid_size_z
  io.task.bits.grid_id_x := grid_counter_x
  io.task.bits.grid_id_y := grid_counter_y
  io.task.bits.grid_id_z := grid_counter_z
  io.task.bits.private_segment_size := queue.private_segment_size
  io.task.bits.group_segment_size := queue.group_segment_size
  io.task.bits.kernel_object := queue.kernel_object
  io.task.bits.kernargs_address := queue.kernargs_address

  // state action
  switch(state) {
    is(Idle) {
      grid_counter_x := 0.U
      grid_counter_y := 0.U
      grid_counter_z := 0.U
      when(io.queue.fire) {
        queue := io.queue.bits
      }
    }
    is(Working) {
      when(io.task.fire) {
        when(grid_x_acc) {
          grid_counter_x := grid_counter_x + 1.U
        }.otherwise {
          grid_counter_x := 0.U
        }

        when(grid_y_acc) {
          grid_counter_y := grid_counter_y + 1.U
        }

        when(grid_z_acc) {
          grid_counter_z := grid_counter_z + 1.U
        }
      }
    }
  }

  io.task_resp.ready := state_rec === s_rec_working

  switch(state_rec) {
    is(s_rec_idle) {
      when(io.task.fire) {
        state_rec := s_rec_working
      }
    }
    is(s_rec_working) {
      when(recDone & io.task_resp.fire) {
        state_rec := s_rec_finish
      }
    }
    is(s_rec_finish) {
      state_rec := s_rec_idle
    }
  }

  switch(state_rec) {
    is(s_rec_idle) {
      grid_rcounter_x := 0.U
      grid_rcounter_y := 0.U
      grid_rcounter_z := 0.U
    }
    is(s_rec_working) {
      when(io.task_resp.fire) {
        when(grid_x_racc) {
          grid_rcounter_x := grid_rcounter_x + 1.U
        }

        when(grid_y_racc) {
          grid_rcounter_y := grid_rcounter_y + 1.U
        }

        when(grid_z_racc) {
          grid_rcounter_z := grid_rcounter_z + 1.U
        }
      }
      when(recDone & io.task_resp.fire) {
        // io.intr.valid := true.B
      }
    }
    is(s_rec_finish) {
      // io.intr.valid := false.B
    }
  }

  // ===== 队列响应逻辑 =====
  // 当所有工作组完成时，向队列发送完成响应
  io.queue_resp.valid := state_rec === s_rec_finish
  io.queue_resp.bits.finish := true.B
}
