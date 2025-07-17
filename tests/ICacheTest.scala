import chisel3._
import ogpu.core._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.VCDHackedEphemeralSimulator._

class ICacheTest extends AnyFlatSpec {
  val param = ICacheParameter(
    useAsyncReset = false,
    prefetch = true,
    nSets = 64,
    nWays = 4,
    blockBytes = 64,
    usingVM = true,
    vaddrBits = 32,
    paddrBits = 32
  )

  behavior.of("ICache")

  it should "handle basic cache operations correctly" in {
    simulate(new ICache(param), "icachetest1") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)
      dut.io.clock_enabled.poke(true.B)

      // Initial state check
      dut.io.req.ready.expect(true.B)

      // Send cache request
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.addr.poke(0x1000.U)
      dut.io.s1_paddr.poke(0x1000.U)
      dut.io.s2_vaddr.poke(0x1000.U)
      dut.io.s1_kill.poke(false.B)
      dut.io.s2_kill.poke(false.B)
      dut.io.s2_cacheable.poke(true.B)
      dut.io.s2_prefetch.poke(false.B)
      dut.io.clock.step(1)
      dut.io.req.valid.poke(false.B)

      // First access should miss
      dut.io.clock.step(5)
      dut.io.instructionFetchAXI.ar.valid.expect(true.B)
      dut.io.instructionFetchAXI.ar.bits.addr.expect(0x1000.U)
      dut.io.clock.step(5)
      dut.io.instructionFetchAXI.ar.ready.poke(true.B)

      // Provide refill data
      dut.io.instructionFetchAXI.r.valid.poke(true.B)
      dut.io.instructionFetchAXI.r.bits.data.poke("h_dead_beef".U)
      dut.io.instructionFetchAXI.r.bits.last.poke(true.B)
      dut.io.clock.step()
      dut.io.instructionFetchAXI.r.valid.poke(false.B)

      // Second access should hit
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.addr.poke(0x1000.U)
      dut.io.clock.step(5)
      dut.io.resp.valid.expect(true.B)

      // Test invalidation
      dut.io.invalidate.poke(true.B)
      dut.io.clock.step()
      dut.io.invalidate.poke(false.B)

      // Access after invalidation should miss
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.addr.poke(0x1000.U)
      dut.io.clock.step()
      dut.io.instructionFetchAXI.ar.valid.expect(true.B)
    }
  }
}
