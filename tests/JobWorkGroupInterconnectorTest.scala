package ogpu.dispatcher

import chisel3._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.VCDHackedEphemeralSimulator._

class JobWorkGroupInterconnectorTest extends AnyFlatSpec {
  val param = JobWorkGroupParameter(
    useAsyncReset = false,
    clockGate = false,
    numJobPorts = 3, // More job ports than WG ports
    numWGPorts = 2
  )

  behavior.of("JobWorkGroupInterconnector")

  it should "route single job request to workgroup port correctly" in {
    simulate(new JobWorkGroupInterconnector(param), "jobworkgroupsingle") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)
      dut.io.wg(0).ready.poke(true.B)
      dut.io.wg(1).ready.poke(true.B)
      dut.io.clock.step()

      // Send request from job 0
      dut.io.job(0).valid.poke(true.B)
      dut.io.job(0).bits.grid_size_x.poke(2.U)
      dut.io.job(0).bits.workgroup_size_x.poke(32.U)

      // Verify routing to WG port
      dut.io.wg(0).valid.expect(true.B)
      dut.io.wg(0).bits.grid_size_x.expect(2.U)
      dut.io.wg(0).bits.workgroup_size_x.expect(32.U)

      // Send response back
      dut.io.wg_resp(0).valid.poke(true.B)
      dut.io.job_resp(0).ready.poke(true.B)
      dut.io.clock.step()

      // Verify response routing
      dut.io.job_resp(0).valid.expect(true.B)
    }
  }

  it should "handle multiple concurrent job requests" in {
    simulate(new JobWorkGroupInterconnector(param), "jobworkgroupmultiple") { dut =>
      // Reset
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Send multiple job requests
      dut.io.job(0).valid.poke(true.B)
      dut.io.job(1).valid.poke(true.B)
      dut.io.job(0).bits.grid_size_x.poke(2.U)
      dut.io.job(1).bits.grid_size_x.poke(3.U)

      // WG ports ready
      dut.io.wg(0).ready.poke(true.B)
      dut.io.wg(1).ready.poke(true.B)

      // Verify routing to both WG ports
      dut.io.wg(0).valid.expect(true.B)
      dut.io.clock.step()
      dut.io.wg(1).valid.expect(true.B)

      // Send third job request
      dut.io.job(2).valid.poke(true.B)
      dut.io.job(2).bits.grid_size_x.poke(4.U)
      dut.io.clock.step()

      // Should wait as both WG ports busy
      dut.io.wg(0).valid.expect(false.B)

      // Complete first WG task
      dut.io.wg_resp(0).valid.poke(true.B)
      dut.io.job_resp(0).ready.poke(true.B)
      dut.io.job_resp(1).ready.poke(true.B)
      dut.io.clock.step()

      // Third job should now route to freed WG port
      dut.io.wg(0).valid.expect(true.B)
      dut.io.wg(0).bits.grid_size_x.expect(2.U)
    }
  }
}
