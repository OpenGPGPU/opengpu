package ogpu.core

import chisel3._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.VCDHackedEphemeralSimulator._

class WarpFrontendTest extends AnyFlatSpec {
  val param = WarpFrontendParameter(
    useAsyncReset = false,
    clockGate = false,
    warpNum = 4,
    vaddrBits = 32,
    vaddrBitsExtended = 32,
    entries = 2,
    coreInstBits = 32,
    fetchWidth = 4,
    fetchBufferSize = 4
  )

  behavior.of("WarpFront")

  it should "handle basic instruction fetch correctly" in {
    simulate(new WarpFrontend(param), "warpfrontend_fetch") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)
      dut.io.reg_init_done.poke(false.B)

      // Start warp
      dut.io.warp_start.valid.poke(true.B)
      dut.io.warp_start.bits.wid.poke(0.U)
      dut.io.warp_start.bits.pc.poke("h1000".U)
      dut.io.clock.step()
      dut.io.warp_start.valid.poke(false.B)

      // Signal register initialization done
      dut.io.reg_init_done.poke(true.B)
      dut.io.clock.step()

      // Check frontend request
      dut.io.frontend_req.valid.expect(true.B)
      dut.io.frontend_req.bits.pc.expect("h1000".U)
      dut.io.frontend_req.bits.wid.expect(0.U)
      dut.io.clock.step()

      // Provide frontend response
      dut.io.frontend_resp.valid.poke(true.B)
      dut.io.frontend_resp.bits.data.poke("h_dead_beef".U)
      dut.io.frontend_resp.bits.pc.poke("h1000".U)
      dut.io.frontend_resp.bits.wid.poke(0.U)
      dut.io.clock.step()
      dut.io.frontend_resp.valid.poke(false.B)
      dut.io.clock.step(5)

      // Check decoder output
      dut.io.decode.valid.expect(true.B)
      dut.io.decode.bits.inst.expect("hdeadbeef".U)
      dut.io.decode.bits.pc.expect("h1000".U)
      dut.io.decode.bits.wid.expect(0.U)
    }
  }

  it should "handle branch instructions correctly" in {
    simulate(new WarpFrontend(param), "warpfrontend_branch") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)
      dut.io.reg_init_done.poke(true.B)
      dut.io.frontend_req.ready.poke(true.B)

      // Start warp
      dut.io.warp_start.valid.poke(true.B)
      dut.io.warp_start.bits.wid.poke(0.U)
      dut.io.warp_start.bits.pc.poke("h1000".U)
      dut.io.clock.step()
      dut.io.warp_start.valid.poke(false.B)
      dut.io.clock.step(5)

      // Simulate normal fetch
      dut.io.frontend_resp.valid.poke(true.B)
      dut.io.frontend_resp.bits.data.poke("hbeef0000".U)
      dut.io.frontend_resp.bits.pc.poke("h1000".U)
      dut.io.clock.step()
      dut.io.frontend_resp.valid.poke(false.B)

      // Verify fetch stops
      dut.io.frontend_req.valid.expect(false.B)

      // Provide branch resolution
      dut.io.branch_update.valid.poke(true.B)
      dut.io.branch_update.bits.wid.poke(0.U)
      dut.io.branch_update.bits.pc.poke("h1000".U)
      dut.io.branch_update.bits.target.poke("h2000".U)
      dut.io.clock.step()
      dut.io.branch_update.valid.poke(false.B)

      // Verify fetch resumes from new target
      dut.io.frontend_req.valid.expect(true.B)
      dut.io.frontend_req.bits.pc.expect("h2000".U)
    }
  }

  it should "handle multiple warps correctly" in {
    simulate(new WarpFrontend(param), "warpfrontend_multiwarps") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)
      dut.io.reg_init_done.poke(true.B)

      // Start first warp
      dut.io.warp_start.valid.poke(true.B)
      dut.io.warp_start.bits.wid.poke(0.U)
      dut.io.warp_start.bits.pc.poke("h1000".U)
      dut.io.clock.step()

      // Start second warp
      dut.io.warp_start.bits.wid.poke(1.U)
      dut.io.warp_start.bits.pc.poke("h2000".U)
      dut.io.clock.step()
      dut.io.warp_start.valid.poke(false.B)

      // Provide response for first warp
      dut.io.frontend_resp.valid.poke(true.B)
      dut.io.frontend_resp.bits.data.poke("h11111111".U)
      dut.io.frontend_resp.bits.pc.poke("h1000".U)
      dut.io.frontend_resp.bits.wid.poke(0.U)
      dut.io.clock.step()

      // Check second warp
      dut.io.frontend_req.bits.wid.expect(1.U)
      dut.io.frontend_req.bits.pc.expect("h2000".U)
    }
  }
}
