package ogpu.dispatcher

import chisel3._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.VCDHackedEphemeralSimulator._

class WorkGroupDispatcherTest extends AnyFlatSpec {
  val param = WorkGroupDispatcherParameter(
    useAsyncReset = false,
    clockGate = false,
    warpSize = 32
  )

  behavior.of("WorkGroupDispatcher")

  it should "dispatch workgroups correctly" in {
    simulate(new WorkGroupDispatcher(param), "workgroupdispatcher") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Test initial state
      dut.io.workgroup_task.valid.poke(true.B)
      dut.io.workgroup_task.bits.workgroup_size_x.poke(2.U)
      dut.io.workgroup_task.bits.workgroup_size_y.poke(2.U)
      dut.io.workgroup_task.bits.workgroup_size_z.poke(1.U)
      dut.io.workgroup_task.bits.grid_size_x.poke(4.U)
      dut.io.workgroup_task.bits.grid_size_y.poke(4.U)
      dut.io.workgroup_task.bits.grid_size_z.poke(1.U)
      dut.io.workgroup_task.bits.grid_id_x.poke(0.U)
      dut.io.workgroup_task.bits.grid_id_y.poke(0.U)
      dut.io.workgroup_task.bits.grid_id_z.poke(0.U)

      dut.io.warp_task.ready.poke(true.B)
      dut.io.clock.step()
      dut.io.workgroup_task.valid.poke(false.B)

      // Test dispatching sequence
      dut.io.warp_task.valid.expect(true.B)
      dut.io.warp_task.bits.workgroup_id_x.expect(0.U)
      dut.io.warp_task.bits.workgroup_id_y.expect(0.U)
      dut.io.warp_task.bits.workgroup_id_z.expect(0.U)

      dut.io.clock.step()
      dut.io.warp_task.bits.workgroup_id_x.expect(1.U)
      dut.io.warp_task.bits.workgroup_id_y.expect(0.U)
      dut.io.warp_task.bits.workgroup_id_z.expect(0.U)

      dut.io.clock.step()
      dut.io.warp_task.bits.workgroup_id_x.expect(0.U)
      dut.io.warp_task.bits.workgroup_id_y.expect(1.U)
      dut.io.warp_task.bits.workgroup_id_z.expect(0.U)

      dut.io.clock.step()
      dut.io.warp_task.bits.workgroup_id_x.expect(1.U)
      dut.io.warp_task.bits.workgroup_id_y.expect(1.U)
      dut.io.warp_task.bits.workgroup_id_z.expect(0.U)

      // Test completion
      dut.io.warp_task_resp.valid.poke(true.B)
      dut.io.clock.step(4)

      // Verify completion
      dut.io.workgroup_task.ready.expect(true.B)
    }
  }
}
