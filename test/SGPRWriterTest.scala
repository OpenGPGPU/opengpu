import chisel3._
import chisel3.util._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.VCDHackedEphemeralSimulator._
import ogpu.core._

class SGPRWriterTest extends AnyFlatSpec {
  val param = SGPRWriterParameter(
    useAsyncReset = true,
    threadNum = 32,
    warpNum = 4,
    dimNum = 2,
    regNum = 16,
    xLen = 32,
    addrBits = 16
  )

  behavior.of("SGPRWriter")

  it should "initialize correctly" in {
    simulate(new SGPRWriter(param), "sgprwriter1") { dut =>
      // Initialize inputs
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)
      dut.io.clock.step()

      // Check initial state
      dut.io.idle.expect(true.B)
      dut.io.commit_data.valid.expect(false.B)
      dut.io.finish.valid.expect(false.B)
    }
  }

  it should "handle warp commands correctly" in {
    simulate(new SGPRWriter(param), "sgprwriter2") { dut =>
      dut.io.clock.step()
      // Send a warp command
      dut.io.warp_cmd.valid.poke(true.B)
      dut.io.warp_cmd.bits.sgpr_num.poke(1.U)
      dut.io.warp_cmd.bits.mask(0).poke(true.B)
      dut.io.clock.step()

      // Check the state after sending the command
      dut.io.idle.expect(false.B)

      // Check commit data after warp command
      dut.io.commit_data.valid.expect(true.B)
      dut.io.commit_data.bits.wid.expect(0.U)
      dut.io.commit_data.bits.rd.expect(0.U)
      dut.io.commit_data.bits.data.expect(0.U)
      dut.io.commit_data.bits.mask.expect(true.B)
    }
  }

  it should "handle commit data correctly" in {
    simulate(new SGPRWriter(param), "sgprwriter3") { dut =>
      // Prepare to send commit data
      dut.io.commit_data.ready.poke(true.B)
      dut.io.clock.step()

      // Check the state after preparing to send commit data
      dut.io.commit_data.valid.expect(false.B)

      // Send a warp command to trigger commit data
      dut.io.warp_cmd.valid.poke(true.B)
      dut.io.warp_cmd.bits.vgpr_num.poke(1.U)
      dut.io.warp_cmd.bits.mask(0).poke(true.B)
      dut.io.clock.step()

      // Check commit data after warp command
      dut.io.commit_data.valid.expect(false.B)
      dut.io.finish.valid.expect(true.B)
    }
  }

  it should "handle finish signal correctly" in {
    simulate(new SGPRWriter(param), "sgprwriter4") { dut =>
      // Prepare to send finish signal
      dut.io.finish.ready.poke(true.B)
      dut.io.commit_data.ready.poke(true.B)
      dut.io.clock.step()

      // Check the state after preparing to send finish signal
      dut.io.finish.valid.expect(false.B)

      // Send a warp command to trigger finish signal
      dut.io.warp_cmd.valid.poke(true.B)
      dut.io.warp_cmd.bits.sgpr_num.poke(3.U)
      dut.io.warp_cmd.bits.mask(0).poke(true.B)
      dut.io.clock.step()

      // Check finish signal after warp command
      dut.io.finish.valid.expect(false.B)
      dut.io.commit_data.ready.poke(true.B)
      dut.io.clock.step()
      dut.io.commit_data.bits.rd.expect(1.U)
      dut.io.clock.step()
      dut.io.commit_data.valid.expect(true.B)
      dut.io.clock.step()
      dut.io.finish.valid.expect(true.B)
      dut.io.finish.ready.poke(true.B)
      dut.io.clock.step()
      dut.io.finish.valid.expect(false.B)
    }
  }
}
