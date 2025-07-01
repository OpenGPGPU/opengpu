package ogpu.core

import chisel3._
import chisel3.simulator.VCDHackedEphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class WarpScoreboardTest extends AnyFlatSpec {
  val warpNum = 4
  val regNum = 8
  val opNum = 2

  behavior.of("WarpScoreboard")

  it should "set and clear busy bits for different warps independently" in {
    simulate(new WarpScoreboard(WarpScoreboardParameter(warpNum, regNum, opNum = opNum)), "warp_scoreboard_set_clear") {
      dut =>
        dut.io.clock.step()
        dut.io.reset.poke(true.B)
        dut.io.clock.step()
        dut.io.reset.poke(false.B)

        // Set reg 3 busy for warp 1
        dut.io.set.en.poke(true.B)
        dut.io.set.warpID.poke(1.U)
        dut.io.set.addr.poke(3.U)
        dut.io.clear.en.poke(false.B)
        dut.io.clock.step()
        dut.io.set.en.poke(false.B)

        // Check busy for warp 1, reg 3
        dut.io.read.warpID.poke(1.U)
        dut.io.read.addr(0).poke(3.U)
        dut.io.read.addr(1).poke(0.U)
        dut.io.read.busy(0).expect(true.B)

        // Check busy for warp 0, reg 3 (should be false)
        dut.io.read.warpID.poke(0.U)
        dut.io.read.addr(0).poke(3.U)
        dut.io.read.busy(0).expect(false.B)

        // Clear reg 3 for warp 1
        dut.io.clear.en.poke(true.B)
        dut.io.clear.warpID.poke(1.U)
        dut.io.clear.addr.poke(3.U)
        dut.io.clock.step()
        dut.io.clear.en.poke(false.B)

        // Check busy for warp 1, reg 3 is cleared
        dut.io.read.warpID.poke(1.U)
        dut.io.read.addr(0).poke(3.U)
        dut.io.read.busy(0).expect(false.B)
    }
  }

  it should "support multiple opNum read ports per warp" in {
    simulate(
      new WarpScoreboard(WarpScoreboardParameter(warpNum, regNum, opNum = opNum)),
      "warp_scoreboard_multi_read"
    ) { dut =>
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Set reg 2 and 4 busy for warp 2
      dut.io.set.en.poke(true.B)
      dut.io.set.warpID.poke(2.U)
      dut.io.set.addr.poke(2.U)
      dut.io.clear.en.poke(false.B)
      dut.io.clock.step()
      dut.io.set.en.poke(true.B)
      dut.io.set.addr.poke(4.U)
      dut.io.clock.step()
      dut.io.set.en.poke(false.B)

      // Check busy for both regs in warp 2
      dut.io.read.warpID.poke(2.U)
      dut.io.read.addr(0).poke(2.U)
      dut.io.read.addr(1).poke(4.U)
      dut.io.read.busy(0).expect(true.B)
      dut.io.read.busy(1).expect(true.B)

      // Clear reg 2 for warp 2
      dut.io.clear.en.poke(true.B)
      dut.io.clear.warpID.poke(2.U)
      dut.io.clear.addr.poke(2.U)
      dut.io.clock.step()
      dut.io.clear.en.poke(false.B)

      // Check reg 2 is cleared, reg 4 still busy
      dut.io.read.warpID.poke(2.U)
      dut.io.read.addr(0).poke(2.U)
      dut.io.read.addr(1).poke(4.U)
      dut.io.read.busy(0).expect(false.B)
      dut.io.read.busy(1).expect(true.B)
    }
  }
}
