package ogpu.core

import chisel3._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.VCDHackedEphemeralSimulator._

class TLBTest extends AnyFlatSpec {
  val param = TLBParameter(
    useAsyncReset = false,
    xLen = 64,
    nSets = 4,
    nWays = 4,
    nSectors = 2,
    nSuperpageEntries = 4,
    asidBits = 0,
    pgLevels = 3,
    usingAtomics = true,
    usingDataScratchpad = false,
    usingAtomicsOnlyForIO = false,
    usingVM = true,
    usingAtomicsInCache = true,
    paddrBits = 32,
    isITLB = true
  )

  behavior.of("TLB")

  it should "initialize correctly" in {
    simulate(new TLB(param), "tlb_init") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)
      dut.io.clock.step()

      // Check initial state
      dut.io.req.ready.expect(true.B)
      dut.io.resp.miss.expect(false.B)
      dut.io.ptw.req.valid.expect(false.B)
    }
  }

  it should "handle basic address translation" in {
    simulate(new TLB(param), "tlb_translation") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Set up PTW status
      dut.io.ptw.ptbr.mode.poke(0x8.U)
      dut.io.ptw.req.ready.poke(1.B)
      dut.io.ptw.status.mxr.poke(false.B)
      dut.io.ptw.status.sum.poke(false.B)

      // Send translation request
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.vaddr.poke("h1033".U)
      dut.io.req.bits.size.poke(3.U)
      dut.io.req.bits.cmd.poke("b00000".U) // Read command
      dut.io.req.bits.passthrough.poke(true.B)
      while (dut.io.req.ready.peek().litToBoolean == false) {
        dut.io.clock.step(1)
      }
      dut.io.clock.step(1)
      dut.io.resp.paddr.expect(0x1033.U)
      dut.io.req.bits.passthrough.poke(false.B)
      while (dut.io.req.ready.peek().litToBoolean == false) {
        dut.io.clock.step(1)
      }
      // First access should miss
      dut.io.clock.step()
      println(s" tlb return miss? ${dut.io.resp.miss.peek().litToBoolean}")
      println(s" tlb return paddr ${dut.io.resp.paddr.peek()}")
      dut.io.resp.miss.expect(true.B)
      dut.io.ptw.req.valid.expect(true.B)
      dut.io.clock.step(5)

      // Provide PTW response
      dut.io.ptw.resp.valid.poke(true.B)
      dut.io.ptw.resp.bits.pte.ppn.poke("h002".U)
      dut.io.ptw.resp.bits.pte.v.poke(true.B)
      dut.io.ptw.resp.bits.pte.r.poke(true.B)
      dut.io.ptw.resp.bits.level.poke(0x2.U)
      dut.io.clock.step()
      dut.io.ptw.resp.valid.poke(false.B)

      // Second access should hit
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.vaddr.poke("h1033".U)
      dut.io.clock.step()
      dut.io.resp.miss.expect(false.B)
      dut.io.resp.paddr.expect("h2033".U)
    }
  }

  it should "handle page faults correctly" in {
    simulate(new TLB(param), "tlb_pagefault") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Enable VM
      // Set up PTW status
      dut.io.ptw.ptbr.mode.poke(0x8.U)
      dut.io.ptw.req.ready.poke(1.B)

      // Send request for invalid page
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.vaddr.poke("h5000".U)
      dut.io.req.bits.cmd.poke("b00000".U)
      while (dut.io.req.ready.peek().litToBoolean == false) {
        dut.io.clock.step(1)
      }
      dut.io.clock.step()
      dut.io.ptw.req.valid.expect(true.B)

      dut.io.clock.step(5)

      // PTW responds with page fault
      dut.io.ptw.resp.valid.poke(true.B)
      dut.io.ptw.resp.bits.level.poke(0x2.U)
      dut.io.ptw.resp.bits.pte.v.poke(true.B)
      // dut.io.ptw.resp.bits.pf.poke(true.B)

      dut.io.clock.step()
      dut.io.ptw.resp.valid.poke(false.B)
      dut.io.clock.step()
      dut.io.resp.miss.expect(false.B)
      dut.io.req.bits.vaddr.poke("h1111111000".U)
      dut.io.req.bits.cmd.poke("b00000".U)
      while (dut.io.req.ready.peek().litToBoolean == false) {
        dut.io.clock.step(1)
      }
      dut.io.clock.step()
      dut.io.ptw.req.valid.expect(true.B)
      dut.io.clock.step(5)
      // PTW responds with page fault
      dut.io.ptw.resp.valid.poke(true.B)
      dut.io.ptw.resp.bits.level.poke(0x2.U)
      dut.io.ptw.resp.bits.pte.v.poke(true.B)
      dut.io.ptw.resp.bits.pf.poke(true.B)
      dut.io.clock.step()
      dut.io.ptw.resp.valid.poke(false.B)
      dut.io.clock.step()
      dut.io.resp.pf.ld.expect(true.B)
    }
  }

  it should "handle sfence.vma correctly" in {
    simulate(new TLB(param), "tlb_sfence") { dut =>
      // Initialize and fill TLB
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Enable VM
      // Set up PTW status
      dut.io.ptw.ptbr.mode.poke(0x8.U)
      dut.io.ptw.req.ready.poke(1.B)

      // Fill entry
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.vaddr.poke("h4000".U)
      dut.io.clock.step()

      dut.io.ptw.resp.valid.poke(true.B)
      dut.io.ptw.resp.bits.pte.ppn.poke("h5000".U)
      dut.io.ptw.resp.bits.pte.v.poke(true.B)
      dut.io.clock.step()
      dut.io.ptw.resp.valid.poke(false.B)

      // Send sfence
      dut.io.sfence.valid.poke(true.B)
      dut.io.clock.step()
      dut.io.sfence.valid.poke(false.B)

      // Verify entry is invalidated
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.vaddr.poke("h4000".U)
      dut.io.clock.step(5)
      dut.io.resp.miss.expect(true.B)
    }
  }
}
