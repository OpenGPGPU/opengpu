package ogpu.dispatcher

import chisel3._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.VCDHackedEphemeralSimulator._

class WorkgroupCUInterconnectorTest extends AnyFlatSpec {
  val param = WorkgroupCUParameter(
    useAsyncReset = false,
    clockGate = false,
    numWGPorts = 3,
    numCUPorts = 2
  )

  behavior.of("WorkgroupCUInterconnector")

  it should "route single workgroup warps to same CU correctly" in {
    simulate(new WorkgroupCUInterconnector(param), "workgroupcusingle") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)
      dut.io.cu(0).ready.poke(true.B)
      dut.io.cu(1).ready.poke(true.B)
      dut.io.wg_resp(0).ready.poke(true.B)
      dut.io.wg_resp(1).ready.poke(true.B)
      dut.io.wg_resp(2).ready.poke(true.B)
      dut.io.clock.step()

      // Test initial state - all ports should be ready
      dut.io.wg(0).ready.expect(true.B)
      dut.io.wg(1).ready.expect(true.B)
      dut.io.wg(2).ready.expect(true.B)

      // Send first warp from workgroup 0
      dut.io.wg(0).valid.poke(true.B)
      dut.io.wg(0).bits.grid_id_x.poke(1.U)
      dut.io.wg(0).bits.first_warp.poke(true.B)
      dut.io.wg(0).bits.last_warp.poke(false.B)

      // Verify first warp routing
      dut.io.cu(0).valid.expect(true.B)
      dut.io.cu(0).bits.grid_id_x.expect(1.U)
      dut.io.clock.step()
      dut.io.cu_resp(0).valid.poke(true.B)

      // Send middle warp from same workgroup
      dut.io.wg(0).bits.first_warp.poke(false.B)
      dut.io.wg(0).bits.last_warp.poke(false.B)
      dut.io.wg(0).bits.grid_id_x.poke(1.U)
      dut.io.clock.step()

      // Verify routed to same CU
      dut.io.cu(0).valid.expect(true.B)
      dut.io.cu(0).bits.grid_id_x.expect(1.U)
      dut.io.clock.step()

      // Send last warp
      dut.io.wg(0).bits.first_warp.poke(false.B)
      dut.io.wg(0).bits.last_warp.poke(true.B)
      dut.io.clock.step(5)
    }
  }

  it should "handle multiple concurrent workgroups" in {
    simulate(new WorkgroupCUInterconnector(param), "workgroupcumultiple") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)
      dut.io.cu(0).ready.poke(true.B)
      dut.io.cu(1).ready.poke(true.B)
      dut.io.wg_resp(0).ready.poke(true.B)
      dut.io.wg_resp(1).ready.poke(true.B)
      dut.io.wg_resp(2).ready.poke(true.B)
      dut.io.clock.step()

      // Send first warps from two workgroups
      dut.io.wg(0).valid.poke(true.B)
      dut.io.wg(1).valid.poke(true.B)
      dut.io.wg(0).bits.grid_id_x.poke(1.U)
      dut.io.wg(1).bits.grid_id_x.poke(2.U)
      dut.io.wg(0).bits.first_warp.poke(true.B)
      dut.io.wg(1).bits.first_warp.poke(true.B)
      dut.io.wg(0).bits.last_warp.poke(false.B)
      dut.io.wg(1).bits.last_warp.poke(false.B)

      // Verify routing to different CUs
      dut.io.cu(0).valid.expect(true.B)
      dut.io.cu(0).bits.grid_id_x.expect(2.U)
      dut.io.clock.step()
      dut.io.cu(1).valid.expect(true.B)
      dut.io.cu(1).bits.grid_id_x.expect(1.U)

      // Try sending third workgroup (should not be accepted)
      dut.io.clock.step()
      dut.io.wg(2).valid.poke(true.B)
      dut.io.wg(1).valid.poke(false.B)
      dut.io.wg(0).valid.poke(false.B)
      dut.io.wg(2).bits.grid_id_x.poke(3.U)
      dut.io.wg(2).bits.first_warp.poke(true.B)
      dut.io.wg(2).ready.expect(false.B)

      // Complete first two workgroups
      dut.io.wg(0).bits.last_warp.poke(true.B)
      dut.io.wg(1).bits.last_warp.poke(true.B)
      dut.io.clock.step()

      // Send responses
      dut.io.cu_resp(0).valid.poke(true.B)
      dut.io.cu_resp(1).valid.poke(true.B)

      // Verify responses and port release
      dut.io.wg_resp(0).valid.expect(true.B)
      dut.io.wg_resp(1).valid.expect(true.B)
      dut.io.clock.step()

      // Third workgroup should now be accepted
      dut.io.wg(2).ready.expect(true.B)
    }
  }
}
