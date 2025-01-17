import chisel3._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.VCDHackedEphemeralSimulator._
import ogpu.core._

class WarpSchedulerTest extends AnyFlatSpec {
  behavior.of("WarpScheduler")

  it should "initialize correctly" in {
    simulate(new WarpScheduler(WarpParameter(
      useAsyncReset = false,
      clockGate = false,
      warpNum = 4,
      stackDepth = 8,
      xLen = 64,
      dimNum = 3,
      paddrBits = 32,
      pgLevels = 2,
      asidBits = 8,
      threadNum = 32
    ))) { dut =>
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Check initial state
      dut.io.warp_cmd.ready.expect(true.B)
      for (i <- 0 until 4) {
        dut.warp_idle(i).expect(true.B)
        dut.warp_active(i).expect(false.B)
      }
    }
  }

  it should "schedule warps correctly" in {
    simulate(new WarpScheduler(WarpParameter(
      useAsyncReset = false,
      clockGate = false,
      warpNum = 4,
      stackDepth = 8,
      xLen = 64,
      dimNum = 3,
      paddrBits = 32,
      pgLevels = 2,
      asidBits = 8,
      threadNum = 32
    ))) { dut =>
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Test warp scheduling
      val task = new CuTaskBundle(32, 4, 3, 64)
      task.pc := 0x1000.U
      task.vgpr_num := 32.U
      task.sgpr_num := 32.U
      task.lds_size := 1024.U

      // Send task to warp 0
      dut.io.warp_cmd.bits := task
      dut.io.warp_cmd.valid.poke(true.B)
      dut.io.clock.step()

      // Verify warp 0 is active
      dut.warp_idle(0).expect(false.B)
      dut.warp_active(0).expect(true.B)
      dut.warp_pc(0).expect(0x1000.U)
    }
  }
}
