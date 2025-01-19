package ogpu.core

import chisel3._
import chisel3.util._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.VCDHackedEphemeralSimulator._

class WarpSchedulerTest extends AnyFlatSpec {
  val param = WarpParameter(
    useAsyncReset = true,
    clockGate = false,
    warpNum = 4,
    stackDepth = 8,
    xLen = 32,
    dimNum = 2,
    addrBits = 16,
    pgLevels = 2,
    asidBits = 8,
    threadNum = 32
  )

  behavior.of("WarpScheduler")

  it should "initialize to correct default values" in {
    simulate(new WarpScheduler(param), "warpscheduler_init") { dut =>
      // Check initial state
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Verify all warps start idle
      for (i <- 0 until param.warpNum) {
        dut.warp_idle(i).expect(true.B)
        dut.warp_active(i).expect(false.B)
        dut.warp_pc(i).expect(0.U)
      }

      dut.state.expect(dut.s_idle)
      dut.has_idle.expect(true.B)
      dut.has_active.expect(false.B)
    }
  }

  it should "accept warp commands in idle state" in {
    simulate(new WarpScheduler(param), "warpscheduler_cmd") { dut =>
      dut.io.reset.poke(false.B)

      // Send warp command
      dut.io.warp_cmd.valid.poke(true.B)
      dut.io.warp_cmd.bits.vgpr_num.poke(1.U)
      dut.io.warp_cmd.bits.mask(0).poke(true.B)
      dut.io.clock.step()

      // Verify transition to working state
      dut.state.expect(dut.s_working)
      dut.warp_idle(0).expect(false.B)
    }
  }

  it should "coordinate VGPR and SGPR writers" in {
    simulate(new WarpScheduler(param), "warpscheduler_writers") { dut =>
      dut.io.reset.poke(false.B)

      // Send command requiring both writers
      dut.io.warp_cmd.valid.poke(true.B)
      dut.io.warp_cmd.bits.vgpr_num.poke(1.U)
      dut.io.warp_cmd.bits.sgpr_num.poke(1.U)
      dut.io.clock.step()

      // Verify both writers activated
      dut.vgpr_writer.io.warp_cmd.valid.expect(true.B)
      dut.sgpr_writer.io.warp_cmd.valid.expect(true.B)

      // Signal writers finished
      dut.vgpr_writer.io.finish.valid.poke(true.B)
      dut.sgpr_writer.io.finish.valid.poke(true.B)
      dut.io.clock.step()

      // Verify transition to finish state
      dut.state.expect(dut.s_finish)
    }
  }

  it should "handle multiple warps correctly" in {
    simulate(new WarpScheduler(param), "warpscheduler_multiple") { dut =>
      dut.io.reset.poke(false.B)

      // Send commands for multiple warps
      for (i <- 0 until 2) {
        dut.io.warp_cmd.valid.poke(true.B)
        dut.io.warp_cmd.bits.vgpr_num.poke(i.U)
        dut.io.clock.step()

        // Complete the current warp
        dut.vgpr_writer.io.finish.valid.poke(true.B)
        dut.sgpr_writer.io.finish.valid.poke(true.B)
        dut.io.clock.step()
      }

      // Verify warp states
      dut.has_idle.expect(true.B)
      dut.has_active.expect(false.B)
    }
  }

  it should "handle error conditions gracefully" in {
    simulate(new WarpScheduler(param), "warpscheduler_errors") { dut =>
      dut.io.reset.poke(false.B)

      // Try sending command when no idle warps
      for (i <- 0 until param.warpNum) {
        dut.warp_idle(i).poke(false.B)
      }

      dut.io.warp_cmd.valid.poke(true.B)
      dut.io.clock.step()

      // Verify command not accepted
      dut.state.expect(dut.s_idle)

      // Reset and verify recovery
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)
      dut.has_idle.expect(true.B)
    }
  }
}
