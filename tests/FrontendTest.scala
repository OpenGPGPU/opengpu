import chisel3._
import chisel3.util.experimental.BitSet
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.VCDHackedEphemeralSimulator._
import ogpu.core._

class FrontendTest extends AnyFlatSpec {
  val bitset = BitSet.fromRange(0x80000000, 0x20000000)
  val param = FrontendParameter(
    useAsyncReset = false,
    clockGate = false,
    xLen = 32,
    usingAtomics = true,
    usingDataScratchpad = false,
    usingVM = true,
    usingCompressed = true,
    usingBTB = true,
    itlbNSets = 8,
    itlbNWays = 8,
    itlbNSectors = 4,
    itlbNSuperpageEntries = 4,
    blockBytes = 32,
    iCacheNSets = 16,
    iCacheNWays = 4,
    iCachePrefetch = false,
    btbEntries = 32,
    btbNMatchBits = 14,
    btbUpdatesOutOfOrder = false,
    nPages = 4,
    nRAS = 8,
    nPMPs = 8,
    paddrBits = 39,
    pgLevels = 3,
    asidBits = 8,
    bhtParameter = None,
    legal = bitset,
    cacheable = bitset,
    read = bitset,
    write = bitset,
    putPartial = BitSet.empty,
    logic = BitSet.empty,
    arithmetic = BitSet.empty,
    exec = BitSet.empty,
    sideEffects = BitSet.empty
  )

  behavior.of("Frontend")

  it should "handle basic fetch sequence" in {
    simulate(new Frontend(param), "frontendfetch") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Set initial conditions
      dut.io.resetVector.poke(0x1000.U)
      dut.io.nonDiplomatic.cpu.might_request.poke(true.B)
      dut.io.nonDiplomatic.cpu.req.valid.poke(false.B)
      dut.io.nonDiplomatic.ptw.status.prv.poke(3.U)
      dut.io.nonDiplomatic.ptw.status.v.poke(false.B)

      // Verify initial PC
      dut.io.clock.step()
      dut.io.nonDiplomatic.cpu.resp.valid.expect(false.B)

      // Enable fetch
      dut.io.nonDiplomatic.cpu.req.valid.poke(true.B)
      dut.io.nonDiplomatic.cpu.req.bits.pc.poke(0x1000.U)
      dut.io.nonDiplomatic.cpu.req.bits.speculative.poke(false.B)
      dut.io.clock.step()

      // Check fetch queue response
      dut.io.nonDiplomatic.cpu.resp.ready.poke(true.B)
      dut.io.clock.step(5)
    }
  }

  // it should "handle branch prediction" in {
  //   simulate(new Frontend(param), "frontendbranch") { dut =>
  //     // Initialize
  //     dut.io.clock.step()
  //     dut.io.reset.poke(true.B)
  //     dut.io.clock.step()
  //     dut.io.reset.poke(false.B)

  //     // Setup branch prediction
  //     dut.io.resetVector.poke(0x1000.U)
  //     dut.io.nonDiplomatic.cpu.might_request.poke(true.B)
  //     dut.io.nonDiplomatic.cpu.req.valid.poke(true.B)
  //     dut.io.nonDiplomatic.cpu.req.bits.pc.poke(0x1000.U)

  //     // Send branch update
  //     dut.io.nonDiplomatic.cpu.btb_update.valid.poke(true.B)
  //     dut.io.nonDiplomatic.cpu.btb_update.bits.pc.poke(0x1000.U)
  //     dut.io.nonDiplomatic.cpu.btb_update.bits.target.poke(0x2000.U)
  //     dut.io.nonDiplomatic.cpu.btb_update.bits.taken.poke(true.B)
  //     dut.io.clock.step()

  //     // Verify branch prediction
  //     dut.io.nonDiplomatic.cpu.btb_update.valid.poke(false.B)
  //     dut.io.clock.step(3)
  //     dut.io.nonDiplomatic.cpu.resp.ready.poke(true.B)
  //     dut.io.clock.step()
  //   }
  // }
  it should "handle branch prediction" in {
    simulate(new Frontend(param), "frontendbranch") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Setup branch prediction
      dut.io.resetVector.poke(0x1000.U)
      dut.io.nonDiplomatic.cpu.might_request.poke(true.B)
      dut.io.nonDiplomatic.cpu.req.valid.poke(true.B)
      dut.io.nonDiplomatic.cpu.req.bits.pc.poke(0x1000.U)

      // Send branch update
      dut.io.nonDiplomatic.cpu.btb_update.valid.poke(true.B)
      dut.io.nonDiplomatic.cpu.btb_update.bits.pc.poke(0x1000.U)
      dut.io.nonDiplomatic.cpu.btb_update.bits.target.poke(0x2000.U)
      dut.io.nonDiplomatic.cpu.btb_update.bits.taken.poke(true.B)
      dut.io.clock.step()

      // Verify branch prediction
      dut.io.nonDiplomatic.cpu.btb_update.valid.poke(false.B)
      dut.io.clock.step(3)
      dut.io.nonDiplomatic.cpu.resp.ready.poke(true.B)
      dut.io.clock.step()
      dut.io.nonDiplomatic.cpu.req.bits.pc.expect(0x2000.U)
    }
  }

  it should "handle TLB miss scenarios" in {
    simulate(new Frontend(param), "frontendtlbmiss") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Trigger TLB miss
      dut.io.resetVector.poke(0x80000000L.U)
      dut.io.nonDiplomatic.cpu.might_request.poke(true.B)
      dut.io.nonDiplomatic.cpu.req.valid.poke(true.B)
      dut.io.nonDiplomatic.cpu.req.bits.pc.poke(0x80000000L.U)
      dut.io.nonDiplomatic.ptw.status.prv.poke(3.U)

      // Verify PTW request
      dut.io.clock.step(5)
      dut.io.nonDiplomatic.ptw.req.valid.expect(true.B)
      dut.io.nonDiplomatic.ptw.req.bits.vpn.expect(0x80000.U)
    }
  }
}
