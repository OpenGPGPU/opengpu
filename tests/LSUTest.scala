package ogpu.core

import chisel3._
import chisel3.simulator.VCDHackedEphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import ogpu.core.OGPUParameter

class LSUTest extends AnyFlatSpec {

  val param = OGPUParameter(
    Set("rv_i", "rv_f"),
    false,
    false,
    16,
    32
  )

  behavior.of("LSU")

  it should "handle basic load operations correctly" in {
    simulate(new LSU(param), "lsu_load") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Test load operation
      val testVaddr = 0x1000.U
      val testWid = 0.U
      val testRd = 5.U
      val expectedData = 0x12345678.U

      // Setup LSU request
      dut.io.clock.step()
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.wid.poke(testWid)
      dut.io.req.bits.vaddr.poke(testVaddr)
      dut.io.req.bits.cmd.poke(0.U) // load
      dut.io.req.bits.size.poke(2.U) // 4 bytes
      dut.io.req.bits.data.poke(0.U) // not used for loads
      dut.io.req.bits.rd.poke(testRd)
      dut.io.resp.ready.poke(true.B)

      // Simulate DCache ready
      dut.io.dcacheReq.ready.poke(true.B)

      // Wait for request to be accepted
      dut.io.clock.step()
      dut.io.req.valid.poke(false.B)

      // Simulate DCache response
      dut.io.clock.step()
      dut.io.dcacheResp.valid.poke(true.B)
      dut.io.dcacheResp.bits.vaddr.poke(testVaddr)
      dut.io.dcacheResp.bits.data.poke(expectedData)
      dut.io.dcacheResp.bits.exception.poke(false.B)

      dut.io.clock.step()

      // Check LSU response
      dut.io.resp.valid.expect(true.B)
      dut.io.resp.bits.wid.expect(testWid)
      dut.io.resp.bits.vaddr.expect(testVaddr)
      dut.io.resp.bits.data.expect(expectedData)
      dut.io.resp.bits.exception.expect(false.B)
      dut.io.resp.bits.rd.expect(testRd)

      // Consume response
      dut.io.resp.ready.poke(true.B)
      dut.io.clock.step()
      dut.io.dcacheResp.valid.poke(false.B)
    }
  }

  it should "handle basic store operations correctly" in {
    simulate(new LSU(param), "lsu_store") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Test store operation
      val testVaddr = 0x2000.U
      val testWid = 1.U
      val testRd = 7.U
      val storeData = 0xabcd.U

      // Setup LSU request
      dut.io.clock.step()
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.wid.poke(testWid)
      dut.io.req.bits.vaddr.poke(testVaddr)
      dut.io.req.bits.cmd.poke(1.U) // store
      dut.io.req.bits.size.poke(2.U) // 4 bytes
      dut.io.req.bits.data.poke(storeData)
      dut.io.req.bits.rd.poke(testRd)
      dut.io.resp.ready.poke(true.B)

      // Simulate DCache ready
      dut.io.dcacheReq.ready.poke(true.B)

      // Wait for request to be accepted
      dut.io.clock.step()
      dut.io.req.valid.poke(false.B)

      // Simulate DCache response
      dut.io.clock.step()
      dut.io.dcacheResp.valid.poke(true.B)
      dut.io.dcacheResp.bits.vaddr.poke(testVaddr)
      dut.io.dcacheResp.bits.data.poke(0.U) // not used for stores
      dut.io.dcacheResp.bits.exception.poke(false.B)

      dut.io.clock.step()

      // Check LSU response
      dut.io.resp.valid.expect(true.B)
      dut.io.resp.bits.wid.expect(testWid)
      dut.io.resp.bits.vaddr.expect(testVaddr)
      dut.io.resp.bits.data.expect(0.U)
      dut.io.resp.bits.exception.expect(false.B)
      dut.io.resp.bits.rd.expect(testRd)

      // Consume response
      dut.io.resp.ready.poke(true.B)
      dut.io.clock.step()
      dut.io.dcacheResp.valid.poke(false.B)
    }
  }

  it should "handle load operations with exceptions correctly" in {
    simulate(new LSU(param), "lsu_load_exception") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Test load operation with exception
      val testVaddr = 0x3000.U
      val testWid = 2.U
      val testRd = 10.U

      // Setup LSU request
      dut.io.clock.step()
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.wid.poke(testWid)
      dut.io.req.bits.vaddr.poke(testVaddr)
      dut.io.req.bits.cmd.poke(0.U) // load
      dut.io.req.bits.size.poke(2.U) // 4 bytes
      dut.io.req.bits.data.poke(0.U)
      dut.io.req.bits.rd.poke(testRd)
      dut.io.resp.ready.poke(true.B)

      // Simulate DCache ready
      dut.io.dcacheReq.ready.poke(true.B)

      // Wait for request to be accepted
      dut.io.clock.step()
      dut.io.req.valid.poke(false.B)

      // Simulate DCache response with exception
      dut.io.clock.step()
      dut.io.dcacheResp.valid.poke(true.B)
      dut.io.dcacheResp.bits.vaddr.poke(testVaddr)
      dut.io.dcacheResp.bits.data.poke(0.U)
      dut.io.dcacheResp.bits.exception.poke(true.B)

      dut.io.clock.step()

      // Check LSU response
      dut.io.resp.valid.expect(true.B)
      dut.io.resp.bits.wid.expect(testWid)
      dut.io.resp.bits.vaddr.expect(testVaddr)
      dut.io.resp.bits.data.expect(0.U)
      dut.io.resp.bits.exception.expect(true.B)
      dut.io.resp.bits.rd.expect(testRd)

      // Consume response
      dut.io.resp.ready.poke(true.B)
      dut.io.clock.step()
      dut.io.dcacheResp.valid.poke(false.B)
    }
  }

  it should "handle backpressure correctly when DCache is not ready" in {
    simulate(new LSU(param), "lsu_backpressure") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Test backpressure
      val testVaddr = 0x4000.U
      val testWid = 3.U
      val testRd = 15.U

      // Setup LSU request
      dut.io.clock.step()
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.wid.poke(testWid)
      dut.io.req.bits.vaddr.poke(testVaddr)
      dut.io.req.bits.cmd.poke(0.U) // load
      dut.io.req.bits.size.poke(2.U) // 4 bytes
      dut.io.req.bits.data.poke(0.U)
      dut.io.req.bits.rd.poke(testRd)
      dut.io.resp.ready.poke(true.B)

      // DCache not ready initially
      dut.io.dcacheReq.ready.poke(false.B)

      // Request should not be accepted
      dut.io.clock.step()
      dut.io.req.ready.expect(false.B)

      // Now make DCache ready
      dut.io.dcacheReq.ready.poke(true.B)
      dut.io.clock.step()

      // Request should now be accepted
      dut.io.req.valid.poke(false.B)

      // Simulate DCache response
      dut.io.clock.step()
      dut.io.dcacheResp.valid.poke(true.B)
      dut.io.dcacheResp.bits.vaddr.poke(testVaddr)
      dut.io.dcacheResp.bits.data.poke(0xdead.U)
      dut.io.dcacheResp.bits.exception.poke(false.B)

      dut.io.clock.step()

      // Check LSU response
      dut.io.resp.valid.expect(true.B)
      dut.io.resp.bits.wid.expect(testWid)
      dut.io.resp.bits.vaddr.expect(testVaddr)
      dut.io.resp.bits.data.expect(0xdead.U)
      dut.io.resp.bits.exception.expect(false.B)
      dut.io.resp.bits.rd.expect(testRd)

      // Consume response
      dut.io.resp.ready.poke(true.B)
      dut.io.clock.step()
      dut.io.dcacheResp.valid.poke(false.B)
    }
  }

  it should "handle single outstanding request limitation correctly" in {
    simulate(new LSU(param), "lsu_single_outstanding") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // First request
      val testVaddr1 = 0x5000.U
      val testWid1 = 4.U
      val testRd1 = 20.U

      // Setup first LSU request
      dut.io.clock.step()
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.wid.poke(testWid1)
      dut.io.req.bits.vaddr.poke(testVaddr1)
      dut.io.req.bits.cmd.poke(0.U) // load
      dut.io.req.bits.size.poke(2.U) // 4 bytes
      dut.io.req.bits.data.poke(0.U)
      dut.io.req.bits.rd.poke(testRd1)
      dut.io.resp.ready.poke(true.B)

      // Simulate DCache ready
      dut.io.dcacheReq.ready.poke(true.B)

      // Wait for first request to be accepted and fired
      dut.io.clock.step()
      dut.io.req.valid.poke(false.B)

      // Now the LSU should be busy, try to send second request
      val testVaddr2 = 0x6000.U
      val testWid2 = 5.U
      val testRd2 = 25.U

      // Try to send second request while first is still pending
      dut.io.clock.step()
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.wid.poke(testWid2)
      dut.io.req.bits.vaddr.poke(testVaddr2)
      dut.io.req.bits.cmd.poke(0.U) // load
      dut.io.req.bits.size.poke(2.U) // 4 bytes
      dut.io.req.bits.data.poke(0.U)
      dut.io.req.bits.rd.poke(testRd2)

      // Second request should be rejected (busy)
      dut.io.req.ready.expect(false.B)

      // Complete first request
      dut.io.clock.step()
      dut.io.dcacheResp.valid.poke(true.B)
      dut.io.dcacheResp.bits.vaddr.poke(testVaddr1)
      dut.io.dcacheResp.bits.data.poke(0xcafe.U)
      dut.io.dcacheResp.bits.exception.poke(false.B)

      dut.io.clock.step()

      // Check first response
      dut.io.resp.valid.expect(true.B)
      dut.io.resp.bits.wid.expect(testWid1)
      dut.io.resp.bits.vaddr.expect(testVaddr1)
      dut.io.resp.bits.data.expect(0xcafe.U)
      dut.io.resp.bits.exception.expect(false.B)
      dut.io.resp.bits.rd.expect(testRd1)

      // Consume first response
      dut.io.resp.ready.poke(true.B)
      dut.io.clock.step()
      dut.io.dcacheResp.valid.poke(false.B)

      // Now second request should be accepted
      dut.io.req.ready.expect(true.B)
      dut.io.req.valid.poke(false.B)

      // Complete second request
      dut.io.clock.step()
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.wid.poke(testWid2)
      dut.io.req.bits.vaddr.poke(testVaddr2)
      dut.io.req.bits.cmd.poke(0.U) // load
      dut.io.req.bits.size.poke(2.U) // 4 bytes
      dut.io.req.bits.data.poke(0.U)
      dut.io.req.bits.rd.poke(testRd2)

      dut.io.clock.step()
      dut.io.req.valid.poke(false.B)

      dut.io.clock.step()
      dut.io.dcacheResp.valid.poke(true.B)
      dut.io.dcacheResp.bits.vaddr.poke(testVaddr2)
      dut.io.dcacheResp.bits.data.poke(0xface.U)
      dut.io.dcacheResp.bits.exception.poke(false.B)

      dut.io.clock.step()

      // Check second response
      dut.io.resp.valid.expect(true.B)
      dut.io.resp.bits.wid.expect(testWid2)
      dut.io.resp.bits.vaddr.expect(testVaddr2)
      dut.io.resp.bits.data.expect(0xface.U)
      dut.io.resp.bits.exception.expect(false.B)
      dut.io.resp.bits.rd.expect(testRd2)

      // Consume second response
      dut.io.resp.ready.poke(true.B)
      dut.io.clock.step()
      dut.io.dcacheResp.valid.poke(false.B)
    }
  }

  it should "handle different access sizes correctly" in {
    simulate(new LSU(param), "lsu_access_sizes") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Test different access sizes
      val testCases = Seq(
        (0.U, "byte"), // 1 byte
        (1.U, "halfword"), // 2 bytes
        (2.U, "word"), // 4 bytes
        (3.U, "doubleword") // 8 bytes
      )

      for ((size, name) <- testCases) {
        val testVaddr = (0x7000 + (size.litValue.toInt << 2)).U
        val testWid = size
        val testRd = (size.litValue.toInt + 16).U

        // Setup LSU request
        dut.io.clock.step()
        dut.io.req.valid.poke(true.B)
        dut.io.req.bits.wid.poke(testWid)
        dut.io.req.bits.vaddr.poke(testVaddr)
        dut.io.req.bits.cmd.poke(0.U) // load
        dut.io.req.bits.size.poke(size)
        dut.io.req.bits.data.poke(0.U)
        dut.io.req.bits.rd.poke(testRd)
        dut.io.resp.ready.poke(true.B)

        // Simulate DCache ready
        dut.io.dcacheReq.ready.poke(true.B)

        // Wait for request to be accepted
        dut.io.clock.step()
        dut.io.req.valid.poke(false.B)

        // Simulate DCache response
        dut.io.clock.step()
        dut.io.dcacheResp.valid.poke(true.B)
        dut.io.dcacheResp.bits.vaddr.poke(testVaddr)
        dut.io.dcacheResp.bits.data.poke(0x1234.U)
        dut.io.dcacheResp.bits.exception.poke(false.B)

        dut.io.clock.step()

        // Check LSU response
        dut.io.resp.valid.expect(true.B)
        dut.io.resp.bits.wid.expect(testWid)
        dut.io.resp.bits.vaddr.expect(testVaddr)
        dut.io.resp.bits.data.expect(0x1234.U)
        dut.io.resp.bits.exception.expect(false.B)
        dut.io.resp.bits.rd.expect(testRd)

        // Consume response
        dut.io.resp.ready.poke(true.B)
        dut.io.clock.step()
        dut.io.dcacheResp.valid.poke(false.B)
      }
    }
  }
}
