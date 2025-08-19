package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.instantiable

class PTWAdapterInterface(parameter: OGPUParameter) extends Bundle {
  // GPUCache-side simple PTW IO (Adapter is the sink of req and source of resp)
  val gpu = Flipped(new GPUTLBPTWIO(parameter.vaddrBitsExtended - 12, 40))
  // Memory sideband for PTW walk through cache
  val mem = Decoupled(new PTWMemoryReq(40, parameter.xLen))
  val memResp = Flipped(Decoupled(new PTWMemoryResp(40, parameter.xLen)))
}

@instantiable
class PTWAdapter(val parameter: OGPUParameter) extends Module {
  val io = IO(new PTWAdapterInterface(parameter))

  // Instantiate full PTW
  private val ptwParam = PTWParameter(
    useAsyncReset = parameter.useAsyncReset,
    xLen = parameter.xLen,
    asidBits = 9,
    pgLevels = 4,
    usingAtomics = false,
    usingDataScratchpad = false,
    usingAtomicsOnlyForIO = false,
    usingVM = true,
    usingAtomicsInCache = false,
    paddrBits = 40,
    isITLB = false
  )
  val ptw = Module(new PTW(ptwParam))
  ptw.io.clock := clock
  ptw.io.reset := reset

  // Bridge GPU req -> PTW req (wrap vpn into PTWReq inside Valid)
  ptw.io.tlb.req.valid := io.gpu.req.valid
  ptw.io.tlb.req.bits.valid := true.B
  ptw.io.tlb.req.bits.bits.addr := io.gpu.req.bits.vpn
  ptw.io.tlb.req.bits.bits.stage2 := false.B
  io.gpu.req.ready := ptw.io.tlb.req.ready

  // Bridge PTW resp -> GPU resp (use only PTE/valid)
  io.gpu.resp.valid := ptw.io.tlb.resp.valid
  io.gpu.resp.bits.pte := ptw.io.tlb.resp.bits.pte
  io.gpu.resp.bits.valid := ptw.io.tlb.resp.valid

  // PTBR defaults (can be driven from CSR later)
  ptw.io.tlb.ptbr.mode := 0.U
  ptw.io.tlb.ptbr.asid := 0.U
  ptw.io.tlb.ptbr.ppn := 0.U

  // Memory sideband straight through
  io.mem <> ptw.io.mem
  ptw.io.memResp <> io.memResp
}
