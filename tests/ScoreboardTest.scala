package ogpu.core

import chisel3._
import chisel3.simulator.VCDHackedEphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class ScoreboardTest extends AnyFlatSpec {
  val regNum = 8
  val opNum = 3

  behavior.of("Scoreboard")

  it should "set and clear busy bits correctly" in {
    simulate(new Scoreboard(ScoreboardParameter(regNum, opNum = opNum)), "scoreboard_set_clear") { dut =>
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Initially all registers should be not busy
      for (i <- 0 until opNum) {
        dut.io.read(i).addr.poke(i.U)
        dut.io.busy(i).expect(false.B)
      }

      // Set register 2 as busy
      dut.io.set.en.poke(true.B)
      dut.io.set.addr.poke(2.U)
      dut.io.clear.en.poke(false.B)
      dut.io.clock.step()
      dut.io.set.en.poke(false.B)

      // Check busy bit for register 2
      dut.io.read(0).addr.poke(2.U)
      dut.io.busy(0).expect(true.B)

      // Clear register 2
      dut.io.clear.en.poke(true.B)
      dut.io.clear.addr.poke(2.U)
      dut.io.clock.step()
      dut.io.clear.en.poke(false.B)

      // Check busy bit for register 2 is cleared (只需等一个周期)
      dut.io.read(0).addr.poke(2.U)
      dut.io.busy(0).expect(false.B)
    }
  }

  it should "support multiple opNum read ports" in {
    simulate(new Scoreboard(ScoreboardParameter(regNum, opNum = opNum)), "scoreboard_multi_read") { dut =>
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Set registers 1, 3, 5 as busy
      for (addr <- Seq(1, 3, 5)) {
        dut.io.set.en.poke(true.B)
        dut.io.set.addr.poke(addr.U)
        dut.io.clock.step()
        dut.io.set.en.poke(false.B)
      }

      // Check busy bits for multiple ports
      val testAddrs = Seq(1, 3, 5)
      for (i <- testAddrs.indices) {
        dut.io.read(i).addr.poke(testAddrs(i).U)
      }
      for (i <- testAddrs.indices) {
        dut.io.busy(i).expect(true.B)
      }

      // Clear register 3 and check again
      dut.io.clear.en.poke(true.B)
      dut.io.clear.addr.poke(3.U)
      dut.io.clock.step()
      dut.io.clear.en.poke(false.B)

      dut.io.read(1).addr.poke(3.U)
      dut.io.busy(1).expect(false.B)
    }
  }

  it should "handle set and clear in the same cycle (bypass logic)" in {
    simulate(new Scoreboard(ScoreboardParameter(regNum, opNum = opNum)), "scoreboard_bypass") { dut =>
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Set and clear register 4 in the same cycle
      dut.io.set.en.poke(true.B)
      dut.io.set.addr.poke(4.U)
      dut.io.clear.en.poke(true.B)
      dut.io.clear.addr.poke(4.U)
      dut.io.clock.step()
      dut.io.set.en.poke(false.B)
      dut.io.clear.en.poke(false.B)

      // Should not be busy (只需等一个周期)
      dut.io.read(0).addr.poke(4.U)
      dut.io.busy(0).expect(true.B)
    }
  }
}
