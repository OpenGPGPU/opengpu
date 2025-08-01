import chisel3._
import chisel3.util._
import chisel3.simulator.VCDHackedEphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import ogpu.core.{DCache, DCacheParameter, PTW, PTWParameter}
import org.chipsalliance.tilelink.bundle._

import ogpu.core._

/** Top-level module containing both PTW and DCache for testing */
class PTWDCacheTop(ptwParams: PTWParameter, cacheParams: DCacheParameter) extends Module {
  val io = IO(new Bundle {
    // PTW interface without clock/reset
    val ptw_tlb_req = Flipped(Decoupled(Valid(new PTWReq(ptwParams.vpnBits))))
    val ptw_tlb_resp = Valid(new PTWResp(ptwParams.vaddrBits, ptwParams.pgLevels))
    val ptw_tlb_ptbr = Input(new PTBR(ptwParams.xLen, ptwParams.maxPAddrBits, ptwParams.pgIdxBits))
    // 移除PTW的mem接口，因为PTW只通过DCache访存
    // val ptw_mem = Decoupled(new PTWMemoryReq(ptwParams.paddrBits, ptwParams.xLen))
    // val ptw_memResp = Flipped(Decoupled(new PTWMemoryResp(ptwParams.paddrBits, ptwParams.xLen)))

    // Cache interface without clock/reset
    val cache_req = Flipped(Decoupled(new DCacheReq(cacheParams)))
    val cache_resp = Decoupled(new DCacheResp(cacheParams))
    val cache_ptw = new GPUTLBPTWIO(cacheParams.vaddrBits - cacheParams.pageOffsetBits, cacheParams.paddrBits)
    val cache_memory = new DMemoryIO(cacheParams)
    // 移除cache的ptwMem接口，因为这是内部连接
    // val cache_ptwMem = Flipped(Decoupled(new PTWMemoryReq(cacheParams.paddrBits, cacheParams.dataWidth)))
    // val cache_ptwMemResp = Decoupled(new PTWMemoryResp(cacheParams.paddrBits, cacheParams.dataWidth))
  })

  val ptw = Module(new PTW(ptwParams))
  val cache = Module(new DCache(cacheParams))

  // 关键连接：将顶层隐式时钟/复位传递给底层
  ptw.io.clock := clock // 连接隐式时钟到显式端口
  ptw.io.reset := reset // 连接隐式复位到显式端口
  cache.io.clock := clock // 连接隐式时钟到显式端口
  cache.io.reset := reset // 连接隐式复位到显式端口

  // Connect PTW to cache - PTW访存请求发送给DCache
  cache.io.ptwMem <> ptw.io.mem
  cache.io.ptwMemResp <> ptw.io.memResp

  // Connect external interfaces (only functional signals, no clock/reset)
  io.ptw_tlb_req <> ptw.io.tlb.req
  io.ptw_tlb_resp <> ptw.io.tlb.resp
  ptw.io.tlb.ptbr <> io.ptw_tlb_ptbr
  // 移除PTW的mem接口，因为PTW只通过DCache访存
  // io.ptw_mem <> ptw.io.mem
  // io.ptw_memResp <> ptw.io.memResp

  io.cache_req <> cache.io.req
  io.cache_resp <> cache.io.resp
  io.cache_ptw <> cache.io.ptw
  io.cache_memory <> cache.io.memory
  // 移除cache的ptwMem接口，因为这是内部连接
  // io.cache_ptwMem <> cache.io.ptwMem
  // io.cache_ptwMemResp <> cache.io.ptwMemResp
}

class PTWDCacheTest extends AnyFlatSpec {

  behavior.of("PTW through DCache")

  it should "handle page table walk" in {
    val ptwParams = PTWParameter(
      useAsyncReset = false,
      xLen = 64,
      asidBits = 8,
      pgLevels = 3,
      usingAtomics = false,
      usingDataScratchpad = false,
      usingAtomicsOnlyForIO = false,
      usingVM = true,
      usingAtomicsInCache = false,
      paddrBits = 32,
      isITLB = true
    )

    val cacheParams = DCacheParameter(
      useAsyncReset = false,
      nSets = 64,
      nWays = 4,
      blockBytes = 64,
      vaddrBits = 39,
      paddrBits = 32,
      dataWidth = 64,
      nMSHRs = 4,
      nTLBEntries = 64,
      pageBytes = 4096
    )

    simulate(new PTWDCacheTop(ptwParams, cacheParams), "ptw_cache_basic") { dut =>
      // Initialize
      dut.clock.step()
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      // Set up PTBR
      dut.io.ptw_tlb_ptbr.mode.poke(8.U) // SV39 mode
      dut.io.ptw_tlb_ptbr.asid.poke(0.U)
      dut.io.ptw_tlb_ptbr.ppn.poke(0x1000.U)

      // Status removed for GPU

      // Send PTW request
      dut.io.ptw_tlb_req.valid.poke(true.B)
      dut.io.ptw_tlb_req.bits.valid.poke(true.B)
      dut.io.ptw_tlb_req.bits.bits.addr.poke(0x800000.U) // VPN (27 bits)
      dut.io.ptw_tlb_req.bits.bits.stage2.poke(false.B)
      dut.clock.step()
      dut.io.ptw_tlb_req.valid.poke(false.B)

      // Wait for PTW memory request through cache
      var memReqValid = false
      for (i <- 0 until 100) {
        if (!memReqValid) {
          dut.clock.step()
          // 通过cache的memory接口检查是否有访存请求
          memReqValid = dut.io.cache_memory.tilelink.a.valid.peek().litToBoolean
        }
      }
      assert(memReqValid, "PTW memory request not received within 100 cycles")

      // Verify PTW memory request through cache
      dut.io.cache_memory.tilelink.a.bits.address.expect(0x1000100.U) // PTBR base + VPN index offset
      dut.io.cache_memory.tilelink.a.bits.opcode.expect(OpCode.Get)
      dut.io.cache_memory.tilelink.a.bits.size.expect(3.U) // 8 bytes

      // Provide memory response through cache
      dut.io.cache_memory.tilelink.d.valid.poke(true.B)
      dut.io.cache_memory.tilelink.d.bits.data.poke("h0000000000000000".U) // Valid PTE
      dut.io.cache_memory.tilelink.d.bits.opcode.poke(OpCode.AccessAckData)
      dut.io.cache_memory.tilelink.d.bits.param.poke(0.U)
      dut.io.cache_memory.tilelink.d.bits.size.poke(3.U)
      dut.io.cache_memory.tilelink.d.bits.source.poke(5.U) // PTW source
      dut.io.cache_memory.tilelink.d.bits.sink.poke(0.U)
      dut.io.cache_memory.tilelink.d.bits.denied.poke(false.B)
      dut.io.cache_memory.tilelink.d.bits.corrupt.poke(false.B)
      dut.clock.step()
      dut.io.cache_memory.tilelink.d.valid.poke(false.B)

      // Wait for PTW response to TLB
      var respValid = false
      for (i <- 0 until 100) {
        if (!respValid) {
          dut.clock.step()
          respValid = dut.io.ptw_tlb_resp.valid.peek().litToBoolean
        }
      }
      assert(respValid, "PTW response not received within 100 cycles")

      // Verify PTW response
      dut.clock.step()

      // Test should complete without errors
    }
  }

  it should "handle memory errors" in {
    val ptwParams = PTWParameter(
      useAsyncReset = false,
      xLen = 64,
      asidBits = 8,
      pgLevels = 3,
      usingAtomics = false,
      usingDataScratchpad = false,
      usingAtomicsOnlyForIO = false,
      usingVM = true,
      usingAtomicsInCache = false,
      paddrBits = 32,
      isITLB = true
    )

    val cacheParams = DCacheParameter(
      useAsyncReset = false,
      nSets = 64,
      nWays = 4,
      blockBytes = 64,
      vaddrBits = 39,
      paddrBits = 32,
      dataWidth = 64,
      nMSHRs = 4,
      nTLBEntries = 64,
      pageBytes = 4096
    )

    simulate(new PTWDCacheTop(ptwParams, cacheParams), "ptw_cache_error") { dut =>
      // Initialize
      dut.clock.step()
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      // Set up PTBR
      dut.io.ptw_tlb_ptbr.mode.poke(8.U) // SV39 mode
      dut.io.ptw_tlb_ptbr.asid.poke(0.U)
      dut.io.ptw_tlb_ptbr.ppn.poke(0x1000.U)

      // Status removed for GPU

      // Send PTW request
      dut.io.ptw_tlb_req.valid.poke(true.B)
      dut.io.ptw_tlb_req.bits.valid.poke(true.B)
      dut.io.ptw_tlb_req.bits.bits.addr.poke(0x800000.U) // VPN (27 bits)
      dut.io.ptw_tlb_req.bits.bits.stage2.poke(false.B)
      dut.clock.step()
      dut.io.ptw_tlb_req.valid.poke(false.B)

      // Wait for PTW memory request through cache
      var memReqValid2 = false
      for (i <- 0 until 100) {
        if (!memReqValid2) {
          dut.clock.step()
          // 通过cache的memory接口检查是否有访存请求
          memReqValid2 = dut.io.cache_memory.tilelink.a.valid.peek().litToBoolean
        }
      }
      assert(memReqValid2, "PTW memory request not received within 100 cycles")

      // Provide memory response with error through cache
      dut.io.cache_memory.tilelink.d.valid.poke(true.B)
      dut.io.cache_memory.tilelink.d.bits.data.poke("h0000000000000000".U)
      dut.io.cache_memory.tilelink.d.bits.opcode.poke(OpCode.AccessAckData)
      dut.io.cache_memory.tilelink.d.bits.param.poke(0.U)
      dut.io.cache_memory.tilelink.d.bits.size.poke(3.U)
      dut.io.cache_memory.tilelink.d.bits.source.poke(5.U) // PTW source
      dut.io.cache_memory.tilelink.d.bits.sink.poke(0.U)
      dut.io.cache_memory.tilelink.d.bits.denied.poke(false.B)
      dut.io.cache_memory.tilelink.d.bits.corrupt.poke(true.B) // Memory error
      dut.clock.step()
      dut.io.cache_memory.tilelink.d.valid.poke(false.B)

      // Wait for PTW response to TLB
      var respValid2 = false
      for (i <- 0 until 100) {
        if (!respValid2) {
          dut.clock.step()
          respValid2 = dut.io.ptw_tlb_resp.valid.peek().litToBoolean
        }
      }
      assert(respValid2, "PTW response not received within 100 cycles")

      // Verify PTW response indicates access exception
      dut.io.ptw_tlb_resp.bits.ae_ptw.expect(true.B)
      dut.clock.step()
    }
  }
}
