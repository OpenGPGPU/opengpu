package ogpu.dispatcher

import chisel3._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.VCDHackedEphemeralSimulator._

class JobDispatcherTest extends AnyFlatSpec {
  val param = DispatcherParameter(
    useAsyncReset = false,
    clockGate = false,
    bufferNum = 4
  )

  behavior.of("JobDispatcher")

  it should "dispatch tasks correctly" in {
    simulate(new JobDispatcher(param), "jobdispatchertask") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Test initial state
      dut.io.aql.valid.poke(true.B)
      dut.io.aql.bits.grid_size_x.poke(2.U)
      dut.io.aql.bits.grid_size_y.poke(2.U)
      dut.io.aql.bits.grid_size_z.poke(1.U)
      dut.io.aql.bits.workgroup_size_x.poke(32.U)
      dut.io.aql.bits.workgroup_size_y.poke(1.U)
      dut.io.aql.bits.workgroup_size_z.poke(1.U)

      dut.io.task.ready.poke(true.B)
      dut.io.clock.step()
      dut.io.aql.valid.poke(false.B)
      dut.io.task.valid.expect(true.B)

      // Test task dispatching
      dut.io.task.bits.workgroup_size_x.expect(32.U)
      dut.io.task.bits.workgroup_size_y.expect(1.U)
      dut.io.task.bits.workgroup_size_z.expect(1.U)
      dut.io.task.bits.grid_id_x.expect(0.U)
      dut.io.task.bits.grid_id_y.expect(0.U)
      dut.io.task.bits.grid_id_z.expect(0.U)
      dut.io.aql.ready.expect(false.B)

      dut.io.clock.step()
      dut.io.task.bits.workgroup_size_x.expect(32.U)
      dut.io.task.bits.workgroup_size_y.expect(1.U)
      dut.io.task.bits.workgroup_size_z.expect(1.U)
      dut.io.task.bits.grid_id_x.expect(1.U)
      dut.io.task.bits.grid_id_y.expect(0.U)
      dut.io.task.bits.grid_id_z.expect(0.U)

      dut.io.clock.step()
      dut.io.task.ready.poke(false.B)
      dut.io.task.bits.grid_id_x.expect(0.U)
      dut.io.task.bits.grid_id_y.expect(1.U)
      dut.io.task.bits.grid_id_z.expect(0.U)

      dut.io.clock.step()
      dut.io.task.ready.poke(true.B)
      dut.io.task.bits.grid_id_x.expect(0.U)
      dut.io.task.bits.grid_id_y.expect(1.U)
      dut.io.task.bits.grid_id_z.expect(0.U)
      dut.io.clock.step(10)
      dut.io.task_resp.valid.poke(true.B)
      dut.io.clock.step(10)
      dut.io.aql.ready.expect(true.B)

    }
  }
}
