import chisel3._
import chisel3.util.experimental.BitSet
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.VCDHackedEphemeralSimulator._
import ogpu.core._

class FrontendTest extends AnyFlatSpec {
  val bitset = BitSet.fromRange(0x80000000, 0x20000000)
  val param = FrontendParameter(
    warpNum = 4,
    useAsyncReset = false,
    clockGate = false,
    xLen = 32,
    usingAtomics = true,
    usingDataScratchpad = false,
    usingVM = true,
    usingCompressed = true,
    itlbNSets = 8,
    itlbNWays = 8,
    itlbNSectors = 4,
    itlbNSuperpageEntries = 4,
    blockBytes = 32,
    iCacheNSets = 16,
    iCacheNWays = 4,
    iCachePrefetch = false,
    nPages = 4,
    nRAS = 8,
    nPMPs = 8,
    paddrBits = 39,
    pgLevels = 3,
    asidBits = 8,
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
      // physical addr mode
      //
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Set initial conditions
      dut.io.resetVector.poke(0x1000.U)
      dut.io.nonDiplomatic.cpu.might_request.poke(true.B)
      dut.io.nonDiplomatic.cpu.req.valid.poke(false.B)
      dut.io.nonDiplomatic.cpu.req.bits.wid.poke(2.U)
      dut.io.nonDiplomatic.ptw.status.prv.poke(3.U)
      dut.io.nonDiplomatic.ptw.status.v.poke(false.B)

      // Verify initial PC
      dut.io.clock.step()
      dut.io.nonDiplomatic.cpu.resp.valid.expect(false.B)
      dut.io.nonDiplomatic.cpu.resp.ready.poke(true.B)

      // Enable fetch
      dut.io.nonDiplomatic.cpu.req.valid.poke(true.B)
      dut.io.nonDiplomatic.cpu.req.bits.pc.poke(0x1000.U)
      dut.io.clock.step()
      dut.io.nonDiplomatic.cpu.req.valid.poke(false.B)

      dut.io.clock.step(5)
      dut.io.instructionFetchAXI.ar.ready.poke(true.B)

      var i = 0
      while (dut.io.instructionFetchAXI.ar.valid.peek().litToBoolean == true && i < 100) {
        i = i + 1
        dut.io.clock.step()
      }
      dut.io.clock.step()
      dut.io.instructionFetchAXI.r.valid.poke(true.B)
      dut.io.instructionFetchAXI.r.bits.data.poke("h_dead_beef".U)
      dut.io.instructionFetchAXI.r.bits.last.poke(false.B)
      dut.io.clock.step(7)
      dut.io.instructionFetchAXI.r.bits.last.poke(true.B)
      dut.io.clock.step()
      dut.io.instructionFetchAXI.r.valid.poke(false.B)
      while (dut.io.nonDiplomatic.cpu.resp.valid.peek().litToBoolean == false && i < 200) {
        dut.io.clock.step()
        i = i + 1
      }
      dut.io.nonDiplomatic.cpu.resp.bits.pc.expect(0x1000.U)
      dut.io.nonDiplomatic.cpu.resp.bits.data.expect("hdeadbeef".U)
    }
  }

  it should "handle TLB miss scenarios" in {
    simulate(new Frontend(param), "frontendtlbmiss") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      // virtual memory mode
      dut.io.reset.poke(false.B)
      dut.io.nonDiplomatic.ptw.ptbr.mode.poke(true.B)

      // Trigger TLB miss
      dut.io.nonDiplomatic.cpu.might_request.poke(true.B)
      dut.io.nonDiplomatic.cpu.req.valid.poke(true.B)
      dut.io.nonDiplomatic.cpu.req.bits.pc.poke(0x80000000L.U)
      dut.io.clock.step()
      dut.io.nonDiplomatic.cpu.req.valid.poke(false.B)

      // Verify PTW request
      dut.io.clock.step(5)
      dut.io.nonDiplomatic.ptw.req.valid.expect(true.B)
      dut.io.nonDiplomatic.ptw.req.bits.bits.addr.expect(0x80000.U)
      dut.io.nonDiplomatic.ptw.req.ready.poke(true.B)
    }
  }
}
