// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2012-2014 The Regents of the University of California
// SPDX-FileCopyrightText: 2016-2017 SiFive, Inc
// SPDX-FileCopyrightText: 2024 Jiuyang Liu <liu@jiuyang.me>
package ogpu.core

import chisel3._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}
import chisel3.util._

object PTWParameter {
  implicit def rwP: upickle.default.ReadWriter[PTWParameter] = upickle.default.macroRW[PTWParameter]
}

case class PTWParameter(
  useAsyncReset:         Boolean,
  xLen:                  Int,
  asidBits:              Int,
  pgLevels:              Int,
  usingAtomics:          Boolean,
  usingDataScratchpad:   Boolean,
  usingAtomicsOnlyForIO: Boolean,
  usingVM:               Boolean,
  usingAtomicsInCache:   Boolean,
  paddrBits:             Int,
  isITLB:                Boolean)
    extends SerializableModuleParameter {

  def lgMaxSize = log2Ceil(xLen / 8)

  def vpnBits: Int = vaddrBits - pgIdxBits

  def ppnBits: Int = paddrBits - pgIdxBits

  private def vpnBitsExtended: Int = vpnBits + (if (vaddrBits < xLen) 1 else 0)

  def vaddrBitsExtended: Int = vpnBitsExtended + pgIdxBits

  def maxSVAddrBits: Int = pgIdxBits + pgLevels * pgLevelBits

  def vaddrBits: Int = if (usingVM) {
    val v = maxSVAddrBits
    require(v > paddrBits)
    v
  } else {
    // since virtual addresses sign-extend but physical addresses
    // zero-extend, make room for a zero sign bit for physical addresses
    (paddrBits + 1).min(xLen)
  }

  def minPgLevels: Int = {
    val res = xLen match {
      case 32 => 2
      case 64 => 3
    }
    require(pgLevels >= res)
    res
  }

  def pgLevelBits: Int = 10 - log2Ceil(xLen / 32)

  def maxPAddrBits: Int = xLen match {
    case 32 => 34
    case 64 => 56
  }

  def pgIdxBits: Int = 12
}

class PTWInterface(parameter: PTWParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(if (parameter.useAsyncReset) AsyncReset() else Bool())

  /** IO to TLB */
  val tlb = new Bundle {
    val req = Flipped(Decoupled(Valid(new PTWReq(parameter.vpnBits))))
    val resp = Valid(new PTWResp(parameter.vaddrBits, parameter.pgLevels))
    val ptbr = Input(new PTBR(parameter.xLen, parameter.maxPAddrBits, parameter.pgIdxBits))
  }

  /** Memory interface for page table walks through cache */
  val mem = Decoupled(new PTWMemoryReq(parameter.paddrBits, parameter.xLen))
  val memResp = Flipped(Decoupled(new PTWMemoryResp(parameter.paddrBits, parameter.xLen)))
}

@instantiable
class PTW(val parameter: PTWParameter)
    extends FixedIORawModule(new PTWInterface(parameter))
    with SerializableModule[PTWParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {
  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  val vaddrBits = parameter.vaddrBits
  val vaddrBitsExtended = parameter.vaddrBitsExtended
  val pgIdxBits = parameter.pgIdxBits
  val pgLevels = parameter.pgLevels
  val minPgLevels = parameter.minPgLevels
  val pgLevelBits = parameter.pgLevelBits

  val vpnBits = parameter.vpnBits
  val ppnBits = parameter.ppnBits
  val usingAtomics = parameter.usingAtomics
  val usingVM = parameter.usingVM
  val usingDataScratchpad = parameter.usingDataScratchpad
  val usingAtomicsOnlyForIO = parameter.usingAtomicsOnlyForIO
  val instruction = parameter.isITLB
  val usingAtomicsInCache = parameter.usingAtomicsInCache
  val lgMaxSize = parameter.lgMaxSize

  // PTW state machine
  val s_idle :: s_request :: s_wait :: s_response :: Nil = Enum(4)
  val state = RegInit(s_idle)

  // Current request tracking
  val r_req_vpn = Reg(UInt(vpnBits.W))
  val r_req_stage2 = Reg(Bool())
  val r_current_level = Reg(UInt(log2Ceil(pgLevels).W))
  val r_base_ppn = Reg(UInt(ppnBits.W))
  val r_pte = Reg(new PTE)
  val r_error = Reg(Bool())
  val r_fragmented_superpage = Reg(Bool())

  // Memory request tracking
  val r_mem_req_addr = Reg(UInt(parameter.paddrBits.W))
  val r_mem_req_valid = Reg(Bool())
  val r_mem_req_source = Reg(UInt(4.W))

  // PTW response
  val r_resp_valid = Reg(Bool())
  val r_resp_pte = Reg(new PTE)
  val r_resp_level = Reg(UInt(log2Ceil(pgLevels).W))
  val r_resp_ae_ptw = Reg(Bool())
  val r_resp_ae_final = Reg(Bool())
  val r_resp_pf = Reg(Bool())
  val r_resp_fragmented_superpage = Reg(Bool())
  val r_resp_homogeneous = Reg(Bool())

  // TLB interface - all signals need to be driven since interface is Flipped
  io.tlb.req.ready := state === s_idle
  io.tlb.resp.valid := r_resp_valid
  io.tlb.resp.bits.ae_ptw := r_resp_ae_ptw
  io.tlb.resp.bits.ae_final := r_resp_ae_final
  io.tlb.resp.bits.pf := r_resp_pf
  io.tlb.resp.bits.pte := r_resp_pte
  io.tlb.resp.bits.level := r_resp_level
  io.tlb.resp.bits.fragmented_superpage := r_resp_fragmented_superpage
  io.tlb.resp.bits.homogeneous := r_resp_homogeneous

  // PTBR signals are inputs from TLB, not driven by PTW

  // Memory interface through cache
  io.mem.valid := r_mem_req_valid
  io.mem.bits.paddr := r_mem_req_addr
  io.mem.bits.cmd := 0.U // Load operation
  io.mem.bits.size := 3.U // 8 bytes for PTE
  io.mem.bits.data := 0.U // Not used for loads

  io.memResp.ready := state === s_wait

  // PTW state machine logic
  when(io.reset.asBool) {
    state := s_idle
    r_resp_valid := false.B
    r_mem_req_valid := false.B
  }.otherwise {
    switch(state) {
      is(s_idle) {
        when(io.tlb.req.fire) {
          state := s_request
          r_req_vpn := io.tlb.req.bits.bits.addr
          r_req_stage2 := io.tlb.req.bits.bits.stage2
          r_current_level := 0.U
          r_base_ppn := io.tlb.ptbr.ppn
          r_error := false.B
          r_fragmented_superpage := false.B
          r_resp_valid := false.B
        }
      }
      is(s_request) {
        // Calculate page table address for current level
        val ptbr = io.tlb.ptbr
        val baseAddr = Cat(ptbr.ppn, 0.U(pgIdxBits.W))

        // Calculate VPN for current level
        val vpn = r_req_vpn
        val vpnForLevel = vpn >> ((pgLevels - 1).U - r_current_level) * pgLevelBits.U
        val vpnIndex = vpnForLevel(pgLevelBits - 1, 0)

        // Calculate page table entry address
        val pteAddr = baseAddr + (vpnIndex << 3) // 8 bytes per PTE

        // Issue memory request
        r_mem_req_addr := pteAddr
        r_mem_req_valid := true.B
        r_mem_req_source := 0.U // Use source 0 for PTW
        state := s_wait
      }
      is(s_wait) {
        r_mem_req_valid := false.B
        when(io.memResp.fire) {
          // Parse PTE from memory response
          val pteData = io.memResp.bits.data
          val pte = Wire(new PTE)
          pte.v := pteData(0)
          pte.r := pteData(1)
          pte.w := pteData(2)
          pte.x := pteData(3)
          pte.u := pteData(4)
          pte.g := pteData(5)
          pte.a := pteData(6)
          pte.d := pteData(7)
          pte.ppn := pteData(53, 10)
          pte.reserved_for_software := pteData(9, 8)
          pte.reserved_for_future := pteData(63, 54)

          r_pte := pte
          r_error := io.memResp.bits.exception

          // Check if this is a valid PTE
          when(pte.v) {
            when(PTE.table(pte)) {
              // This is a page table entry, continue to next level
              when(r_current_level < (pgLevels - 1).U) {
                r_current_level := r_current_level + 1.U
                r_base_ppn := pte.ppn
                state := s_request
              }.otherwise {
                // Invalid: too many levels
                r_error := true.B
                state := s_response
              }
            }.otherwise {
              // This is a leaf PTE
              when(PTE.leaf(pte)) {
                // Valid leaf PTE found
                state := s_response
              }.otherwise {
                // Invalid PTE
                r_error := true.B
                state := s_response
              }
            }
          }.otherwise {
            // Invalid PTE (V=0)
            r_error := true.B
            state := s_response
          }
        }
      }
      is(s_response) {
        // Prepare response to TLB
        r_resp_valid := true.B
        r_resp_pte := r_pte
        r_resp_level := r_current_level
        r_resp_ae_ptw := r_error
        r_resp_ae_final := r_error
        r_resp_pf := !r_pte.v || (!r_pte.r && !r_pte.x)
        r_resp_fragmented_superpage := r_fragmented_superpage
        r_resp_homogeneous := true.B // For now, assume homogeneous

        when(io.tlb.resp.fire) {
          state := s_idle
          r_resp_valid := false.B
        }
      }
    }
  }

  // Helper functions for PTE validation
  def validatePTE(pte: PTE, level: UInt): Bool = {
    // Basic validation: PTE must be valid and have appropriate permissions
    pte.v && (pte.r || pte.x || pte.w)
  }

  def checkPageFault(pte: PTE, level: UInt): Bool = {
    // Check for page faults based on RISC-V privilege spec
    !pte.v || (!pte.r && !pte.x) || (level === 0.U && !pte.a)
  }

  def checkAccessException(pte: PTE, level: UInt): Bool = {
    // Check for access exceptions
    false.B // Simplified for now
  }
}
