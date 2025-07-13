import chisel3.experimental.{ExtModule, SerializableModule, SerializableModuleGenerator}
import chisel3._
import ogpu.core._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.VCDHackedEphemeralSimulator._

class GPUCacheTest extends AnyFlatSpec {
  val param = GPUCacheParameter(
    useAsyncReset = false,
    nSets = 64,
    nWays = 4,
    blockBytes = 64,
    vaddrBits = 32,
    paddrBits = 32,
    dataWidth = 64,
    nMSHRs = 4,
    nTLBEntries = 64,
    pageBytes = 4096
  )

  behavior.of("GPUCache")

  it should "handle basic cache operations correctly" in {
    simulate(new GPUCache(param), "gpucachetest1") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Initial state check
      dut.io.req.ready.expect(true.B)

      // Send cache request (load)
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.vaddr.poke(0x1000.U)
      dut.io.req.bits.cmd.poke(0.U) // Load
      dut.io.req.bits.size.poke(3.U) // 8 bytes
      dut.io.req.bits.data.poke(0.U)
      dut.io.clock.step(1)
      dut.io.req.valid.poke(false.B)
      dut.io.ptw.req.ready.poke(true.B)
      println("waiting for ptw req")
      // TLB should request PTW for first access
      while (dut.io.ptw.req.valid.peek().litToBoolean == false) {
        dut.io.clock.step(1)
      }
      dut.io.ptw.req.bits.vpn.expect(0x1.U) // VPN for 0x1000

      // Provide PTW response
      dut.io.ptw.resp.valid.poke(true.B)
      dut.io.ptw.resp.bits.pte.ppn.poke(0x2000.U) // Physical page number
      dut.io.ptw.resp.bits.pte.v.poke(true.B) // Valid bit
      dut.io.ptw.resp.bits.pte.r.poke(true.B) // Read permission
      dut.io.ptw.resp.bits.pte.w.poke(true.B) // Write permission
      dut.io.ptw.resp.bits.pte.x.poke(true.B) // Execute permission
      dut.io.ptw.resp.bits.pte.u.poke(true.B) // User mode accessible
      dut.io.ptw.resp.bits.pte.a.poke(true.B) // Accessed bit
      dut.io.ptw.resp.bits.pte.d.poke(true.B) // Dirty bit
      dut.io.ptw.resp.bits.pte.g.poke(false.B) // Global bit
      dut.io.ptw.resp.bits.pte.reserved_for_future.poke(0.U)
      dut.io.ptw.resp.bits.pte.reserved_for_software.poke(0.U)
      dut.io.ptw.resp.bits.valid.poke(true.B)
      dut.io.clock.step()
      dut.io.ptw.resp.valid.poke(false.B)

      println("waiting for memory req")
      // Cache should miss and request from memory
      while (!dut.io.memory.req.valid.peek().litToBoolean) {
        dut.io.clock.step(1)
      }
      val pageOffsetBits = 12 // 4KB page
      val pfn = 0x2000
      val expectedPaddr = pfn << pageOffsetBits
      dut.io.memory.req.bits.addr.expect(expectedPaddr.U) // Physical address
      dut.io.memory.req.bits.cmd.expect(0.U) // Load

      // Provide memory response
      dut.io.memory.resp.valid.poke(true.B)
      dut.io.memory.resp.bits.addr.poke(0x2000.U)
      dut.io.memory.resp.bits.data.poke("h_dead_beef_cafe_babe".U)
      dut.io.memory.resp.bits.valid.poke(true.B)
      dut.io.clock.step()
      dut.io.memory.resp.valid.poke(false.B)

      // Cache should respond with data
      println("waiting for cache resp")
      while (!dut.io.resp.valid.peek().litToBoolean) {
        dut.io.clock.step(1)
      }
      dut.io.resp.bits.vaddr.expect(0x1000.U)
      dut.io.resp.bits.data.expect("h_dead_beef_cafe_babe".U)
      dut.io.resp.bits.exception.expect(false.B)

      // Second access to same address should hit
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.vaddr.poke(0x1000.U)
      dut.io.req.bits.cmd.poke(0.U)
      dut.io.req.bits.size.poke(3.U)
      println("waiting for cache resp2")
      while (!dut.io.resp.valid.peek().litToBoolean) {
        dut.io.clock.step(1)
      }
      dut.io.resp.bits.data.expect("h_dead_beef_cafe_babe".U)
      dut.io.req.valid.poke(false.B)
    }
  }

  // it should "handle store operations correctly" in {
  //   simulate(new GPUCache(param), "gpucachetest2") { dut =>
  //     // Initialize
  //     dut.io.clock.step()
  //     dut.io.reset.poke(true.B)
  //     dut.io.clock.step()
  //     dut.io.reset.poke(false.B)

  //     // First load to bring data into cache
  //     dut.io.req.valid.poke(true.B)
  //     dut.io.req.bits.vaddr.poke(0x2000.U)
  //     dut.io.req.bits.cmd.poke(0.U)
  //     dut.io.req.bits.size.poke(3.U)
  //     dut.io.clock.step(1)
  //     dut.io.req.valid.poke(false.B)

  //     // Provide PTW response
  //     dut.io.ptw.resp.valid.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.ppn.poke(0x3000.U)
  //     dut.io.ptw.resp.bits.pte.v.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.r.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.w.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.x.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.u.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.a.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.d.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.g.poke(false.B)
  //     dut.io.ptw.resp.bits.pte.reserved_for_future.poke(0.U)
  //     dut.io.ptw.resp.bits.pte.reserved_for_software.poke(0.U)
  //     dut.io.ptw.resp.bits.valid.poke(true.B)
  //     dut.io.clock.step()
  //     dut.io.ptw.resp.valid.poke(false.B)

  //     // Provide memory response for load
  //     dut.io.memory.resp.valid.poke(true.B)
  //     dut.io.memory.resp.bits.addr.poke(0x3000.U)
  //     dut.io.memory.resp.bits.data.poke("h_1234567890abcdef".U)
  //     dut.io.memory.resp.bits.valid.poke(true.B)
  //     dut.io.clock.step()
  //     dut.io.memory.resp.valid.poke(false.B)

  //     // Wait for load to complete
  //     dut.io.clock.step(5)

  //     // Now perform store operation
  //     dut.io.req.valid.poke(true.B)
  //     dut.io.req.bits.vaddr.poke(0x2000.U)
  //     dut.io.req.bits.cmd.poke(1.U) // Store
  //     dut.io.req.bits.size.poke(3.U)
  //     dut.io.req.bits.data.poke("h_fedcba0987654321".U)
  //     dut.io.clock.step(1)
  //     dut.io.req.valid.poke(false.B)

  //     // Store should hit and update cache
  //     println("waiting for cache resp")
  //     while (!dut.io.resp.valid.peek().litToBoolean) {
  //       dut.io.clock.step(1)
  //     }
  //     dut.io.resp.bits.exception.expect(false.B)

  //     // Verify data was written by reading it back
  //     dut.io.req.valid.poke(true.B)
  //     dut.io.req.bits.vaddr.poke(0x2000.U)
  //     dut.io.req.bits.cmd.poke(0.U)
  //     dut.io.req.bits.size.poke(3.U)
  //     println("waiting for cache resp2")
  //     while (!dut.io.resp.valid.peek().litToBoolean) {
  //       dut.io.clock.step(1)
  //     }
  //     dut.io.resp.bits.data.expect("h_fedcba0987654321".U)
  //     dut.io.req.valid.poke(false.B)
  //     dut.io.clock.step(5)
  //   }
  // }

  // it should "handle MSHR merging correctly" in {
  //   simulate(new GPUCache(param), "gpucachetest3") { dut =>
  //     // Initialize
  //     dut.io.clock.step()
  //     dut.io.reset.poke(true.B)
  //     dut.io.clock.step()
  //     dut.io.reset.poke(false.B)

  //     // First request to trigger miss
  //     dut.io.req.valid.poke(true.B)
  //     dut.io.req.bits.vaddr.poke(0x4000.U)
  //     dut.io.req.bits.cmd.poke(0.U)
  //     dut.io.req.bits.size.poke(3.U)
  //     dut.io.clock.step(1)
  //     dut.io.req.valid.poke(false.B)

  //     // Provide PTW response
  //     dut.io.ptw.resp.valid.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.ppn.poke(0x5000.U)
  //     dut.io.ptw.resp.bits.pte.v.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.r.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.w.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.x.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.u.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.a.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.d.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.g.poke(false.B)
  //     dut.io.ptw.resp.bits.pte.reserved_for_future.poke(0.U)
  //     dut.io.ptw.resp.bits.pte.reserved_for_software.poke(0.U)
  //     dut.io.ptw.resp.bits.valid.poke(true.B)
  //     dut.io.clock.step()
  //     dut.io.ptw.resp.valid.poke(false.B)

  //     // Wait for MSHR allocation
  //     dut.io.clock.step(3)

  //     // Second request to same block should merge
  //     dut.io.req.valid.poke(true.B)
  //     dut.io.req.bits.vaddr.poke(0x4008.U) // Same block, different offset
  //     dut.io.req.bits.cmd.poke(0.U)
  //     dut.io.req.bits.size.poke(3.U)
  //     dut.io.clock.step(1)
  //     dut.io.req.valid.poke(false.B)

  //     println("waiting for memory req")
  //     // Should not create new memory request
  //     while (!dut.io.memory.req.valid.peek().litToBoolean) {
  //       dut.io.clock.step(1)
  //     }
  //     dut.io.memory.req.valid.expect(true.B) // Only one memory request

  //     // Provide memory response
  //     dut.io.memory.resp.valid.poke(true.B)
  //     dut.io.memory.resp.bits.addr.poke(0x5000.U)
  //     dut.io.memory.resp.bits.data.poke("h_1111222233334444".U)
  //     dut.io.memory.resp.bits.valid.poke(true.B)
  //     dut.io.clock.step()
  //     dut.io.memory.resp.valid.poke(false.B)

  //     // Both requests should be satisfied
  //     println("waiting for cache resp")
  //     while (!dut.io.resp.valid.peek().litToBoolean) {
  //       dut.io.clock.step(1)
  //     }
  //     dut.io.resp.bits.vaddr.expect(0x4000.U)
  //     println("waiting for cache resp2")
  //     while (!dut.io.resp.valid.peek().litToBoolean) {
  //       dut.io.clock.step(1)
  //     }
  //     dut.io.resp.bits.vaddr.expect(0x4008.U)
  //   }
  // }

  // it should "handle TLB misses correctly" in {
  //   simulate(new GPUCache(param), "gpucachetest4") { dut =>
  //     // Initialize
  //     dut.io.clock.step()
  //     dut.io.reset.poke(true.B)
  //     dut.io.clock.step()
  //     dut.io.reset.poke(false.B)

  //     // Request with TLB miss
  //     dut.io.req.valid.poke(true.B)
  //     dut.io.req.bits.vaddr.poke(0x6000.U)
  //     dut.io.req.bits.cmd.poke(0.U)
  //     dut.io.req.bits.size.poke(3.U)
  //     dut.io.clock.step(1)
  //     dut.io.req.valid.poke(false.B)

  //     // TLB should request PTW
  //     println("waiting for ptw")
  //     while (!dut.io.ptw.req.valid.peek().litToBoolean) {
  //       dut.io.clock.step(1)
  //     }
  //     dut.io.ptw.req.bits.vpn.expect(0x6.U)

  //     // Provide invalid PTW response
  //     dut.io.ptw.resp.valid.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.ppn.poke(0.U)
  //     dut.io.ptw.resp.bits.pte.v.poke(false.B) // Invalid translation
  //     dut.io.ptw.resp.bits.pte.r.poke(false.B)
  //     dut.io.ptw.resp.bits.pte.w.poke(false.B)
  //     dut.io.ptw.resp.bits.pte.x.poke(false.B)
  //     dut.io.ptw.resp.bits.pte.u.poke(false.B)
  //     dut.io.ptw.resp.bits.pte.a.poke(false.B)
  //     dut.io.ptw.resp.bits.pte.d.poke(false.B)
  //     dut.io.ptw.resp.bits.pte.g.poke(false.B)
  //     dut.io.ptw.resp.bits.pte.reserved_for_future.poke(0.U)
  //     dut.io.ptw.resp.bits.pte.reserved_for_software.poke(0.U)
  //     dut.io.ptw.resp.bits.valid.poke(false.B) // Invalid translation
  //     dut.io.clock.step()
  //     dut.io.ptw.resp.valid.poke(false.B)

  //     // Should generate exception
  //     println("waiting for cache resp")
  //     while (!dut.io.resp.valid.peek().litToBoolean) {
  //       dut.io.clock.step(1)
  //     }
  //     dut.io.resp.bits.exception.expect(true.B)
  //   }
  // }

  // it should "handle different access sizes correctly" in {
  //   simulate(new GPUCache(param), "gpucachetest5") { dut =>
  //     // Initialize
  //     dut.io.clock.step()
  //     dut.io.reset.poke(true.B)
  //     dut.io.clock.step()
  //     dut.io.reset.poke(false.B)

  //     // Test byte access
  //     dut.io.req.valid.poke(true.B)
  //     dut.io.req.bits.vaddr.poke(0x7000.U)
  //     dut.io.req.bits.cmd.poke(0.U)
  //     dut.io.req.bits.size.poke(0.U) // 1 byte
  //     dut.io.clock.step(1)
  //     dut.io.req.valid.poke(false.B)

  //     // Provide PTW response
  //     dut.io.ptw.resp.valid.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.ppn.poke(0x8000.U)
  //     dut.io.ptw.resp.bits.pte.v.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.r.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.w.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.x.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.u.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.a.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.d.poke(true.B)
  //     dut.io.ptw.resp.bits.pte.g.poke(false.B)
  //     dut.io.ptw.resp.bits.pte.reserved_for_future.poke(0.U)
  //     dut.io.ptw.resp.bits.pte.reserved_for_software.poke(0.U)
  //     dut.io.ptw.resp.bits.valid.poke(true.B)
  //     dut.io.clock.step()
  //     dut.io.ptw.resp.valid.poke(false.B)

  //     // Provide memory response
  //     dut.io.memory.resp.valid.poke(true.B)
  //     dut.io.memory.resp.bits.addr.poke(0x8000.U)
  //     dut.io.memory.resp.bits.data.poke("h_aa".U)
  //     dut.io.memory.resp.bits.valid.poke(true.B)
  //     dut.io.clock.step()
  //     dut.io.memory.resp.valid.poke(false.B)

  //     // Verify response
  //     println("waiting for cache resp")
  //     while (!dut.io.resp.valid.peek().litToBoolean) {
  //       dut.io.clock.step(1)
  //     }
  //     dut.io.resp.bits.data.expect("h_aa".U)
  //   }
  // }
}
