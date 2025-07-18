/** Core Bundle Definitions for OGPU
  *
  * This package contains the main bundle definitions used throughout the OGPU core, including task bundles, stack data
  * structures, and commit data structures.
  */
package ogpu.core

import chisel3._
import chisel3.util._
import org.chipsalliance.rocketv.ICacheErrors

/** Compute Unit Task Bundle
  *
  * Represents a task to be executed by a compute unit, containing all necessary state information for execution.
  *
  * @param threadNum
  *   Number of threads in the warp
  * @param warpNum
  *   Number of warps
  * @param dimNum
  *   Number of dimensions in vector operations
  * @param xLen
  *   Scalar register width in bits
  * @param dimWidth
  *   Vector dimension width in bits (default 16)
  */
class CuTaskBundle(
  threadNum: Int, // Number of threads in the warp
  warpNum:   Int, // Number of warps
  dimNum:    Int, // Number of dimensions in vector operations
  xLen:      Int, // Scalar register width in bits
  dimWidth:  Int = 16) // Vector dimension width in bits
    extends Bundle {
  val mask = Vec(threadNum, Bool()) // Thread active mask
  val pc = UInt(32.W) // Program counter value
  val vgprs = Vec(dimNum, UInt(dimWidth.W)) // Vector general purpose registers
  val vgpr_num = UInt(2.W) // Number of active VGPRs
  val sgprs = Vec(16, UInt(xLen.W)) // Scalar general purpose registers
  val sgpr_num = UInt(4.W) // Number of active SGPRs
}

/** Stack Data Structure
  *
  * Represents the state of a thread stack during divergence, including masks and program counter information.
  *
  * @param threadNum
  *   Number of threads in the warp
  * @param addrBits
  *   Number of bits for address representation
  */
class StackData(
  threadNum: Int, // Number of threads in the warp
  addrBits:  Int) // Address width in bits
    extends Bundle {
  val mask = Vec(threadNum, Bool()) // Current thread divergence mask
  val pc = UInt(addrBits.W) // Program counter at divergence point
  val orig_mask = Vec(threadNum, Bool()) // Original mask before divergence
}

/** Commit Scalar Data Structure
  *
  * Contains the data needed for instruction commit, including register write information and program counter state.
  *
  * @param xLen
  *   Scalar register width in bits
  * @param addrWidth
  *   Address width in bits
  * @param warpNum
  *   Number of warps
  * @param regNum
  *   Number of registers
  */
class CommitSData(
  xLen:     Int, // Scalar register width in bits
  addrBits: Int, // Address width in bits
  warpNum:  Int, // Number of warps
  regNum:   Int) // Number of registers
    extends Bundle {
  val regIDWidth = log2Ceil(regNum) // Width of register ID field

  val wid = UInt(log2Ceil(warpNum).W) // Warp ID
  val mask = Bool() // Commit mask
  val pc = UInt(addrBits.W) // Program counter value
  val rd = UInt(regIDWidth.W) // Destination register ID
  val data = UInt(xLen.W) // Data to be written
}

/** Commit Vector Data Structure
  *
  * Contains the data needed for instruction commit, including register write information and program counter state.
  *
  * @param xLen
  *   Scalar register width in bits
  * @param addrBits
  *   Address width in bits
  * @param warpNum
  *   Number of warps
  * @param regNum
  *   Number of registers
  * @param threadNum
  *   Number of threads
  */
class CommitVData(
  xLen:      Int, // register width in bits
  threadNum: Int, // Number of thread in a warp
  addrBits:  Int, // Address width in bits
  warpNum:   Int, // Number of warps
  regNum:    Int) // Number of registers
    extends Bundle {
  val regIDWidth = log2Ceil(regNum) // Width of register ID field

  val wid = UInt(log2Ceil(warpNum).W) // Warp ID
  val mask = Bool() // Commit mask
  val pc = UInt(addrBits.W) // Program counter value
  val rd = UInt(regIDWidth.W) // Destination register ID
  val data = Vec(threadNum, UInt(xLen.W)) // Data to be written
}

// TODO: make it Enum
object PRV {
  val SZ = 2
  val U = 0
  val S = 1
  val H = 2
  val M = 3
}

class MStatus extends Bundle {
  // not truly part of mstatus, but convenient
  val debug = Bool()
  val cease = Bool()
  val wfi = Bool()
  val isa = UInt(32.W)

  val dprv = UInt(PRV.SZ.W) // effective prv for data accesses
  val dv = Bool() // effective v for data accesses
  val prv = UInt(PRV.SZ.W)
  val v = Bool()

  val sd = Bool()
  val zero2 = UInt(23.W)
  val mpv = Bool()
  val gva = Bool()
  val mbe = Bool()
  val sbe = Bool()
  val sxl = UInt(2.W)
  val uxl = UInt(2.W)
  val sd_rv32 = Bool()
  val zero1 = UInt(8.W)
  val tsr = Bool()
  val tw = Bool()
  val tvm = Bool()
  val mxr = Bool()
  val sum = Bool()
  val mprv = Bool()
  val xs = UInt(2.W)
  val fs = UInt(2.W)
  val mpp = UInt(2.W)
  val vs = UInt(2.W)
  val spp = UInt(1.W)
  val mpie = Bool()
  val ube = Bool()
  val spie = Bool()
  val upie = Bool()
  val mie = Bool()
  val hie = Bool()
  val sie = Bool()
  val uie = Bool()
}

class SFenceReq(vaddrBits: Int, asidBits: Int) extends Bundle {
  val rs1 = Bool()
  val rs2 = Bool()
  val addr = UInt(vaddrBits.W)
  val asid = UInt(asidBits.W)
}

/** IO between TLB and PTW
  *
  * PTW receives :
  *   - PTE request
  *   - CSRs info
  *   - pmp results from PMP(in TLB)
  */
class TLBPTWIO(
  vpnBits:      Int,
  paddrBits:    Int,
  vaddrBits:    Int,
  pgLevels:     Int,
  xLen:         Int,
  maxPAddrBits: Int,
  pgIdxBits:    Int)
    extends Bundle {
  val req = Decoupled(Valid(new PTWReq(vpnBits)))
  val resp = Flipped(Valid(new PTWResp(vaddrBits, pgLevels)))
  val ptbr = Input(new PTBR(xLen, maxPAddrBits, pgIdxBits))
  val status = Input(new MStatus)
}

class TLBReq(lgMaxSize: Int, vaddrBitsExtended: Int)() extends Bundle {
  // TODO: remove it.
  val M_SZ = 5

  /** request address from CPU. */
  val vaddr = UInt(vaddrBitsExtended.W)

  /** don't lookup TLB, bypass vaddr as paddr */
  val passthrough = Bool()

  /** granularity */
  val size = UInt(log2Ceil(lgMaxSize + 1).W)

  /** memory command. */
  val cmd = UInt(M_SZ.W)
}

class TLBResp(paddrBits: Int, vaddrBitsExtended: Int) extends Bundle {
  // lookup responses
  val miss = Bool()

  /** physical address */
  val paddr = UInt(paddrBits.W)

  /** page fault exception */
  val pf = new TLBExceptions

  /** access exception */
  val ae = new TLBExceptions

  /** misaligned access exception */
  val ma = new TLBExceptions

  /** if this address is cacheable */
  val cacheable = Bool()

  /** if caches must allocate this address */
  val must_alloc = Bool()

  /** if this address is prefetchable for caches */
  val prefetchable = Bool()
}

class TLBExceptions extends Bundle {
  val ld = Bool()
  val st = Bool()
  val inst = Bool()
}

object TLBEntry {

  /** returns all entry data in this entry */
  def entry_data(tlbEntry: TLBEntry) = tlbEntry.data.map(_.asTypeOf(new TLBEntryData(tlbEntry.ppnBits)))

  /** returns the index of sector */
  private def sectorIdx(tlbEntry: TLBEntry, vpn: UInt) = vpn(log2Ceil(tlbEntry.nSectors) - 1, 0)

  /** returns the entry data matched with this vpn */
  def getData(tlbEntry: TLBEntry, vpn: UInt) =
    tlbEntry.data(sectorIdx(tlbEntry, vpn)).asTypeOf(new TLBEntryData(tlbEntry.ppnBits))

  /** returns whether a sector hits */
  def sectorHit(tlbEntry: TLBEntry, vpn: UInt) =
    tlbEntry.valid.asUInt.orR && sectorTagMatch(tlbEntry, vpn)

  /** returns whether tag matches vpn */
  def sectorTagMatch(tlbEntry: TLBEntry, vpn: UInt) =
    (((tlbEntry.tag_vpn ^ vpn) >> log2Ceil(tlbEntry.nSectors)) === 0.U)

  /** returns hit signal */
  def hit(
    tlbEntry:                TLBEntry,
    vpn:                     UInt,
    usingVM:                 Boolean,
    pgLevelBits:             Int,
    hypervisorExtraAddrBits: Int,
    superpage:               Boolean,
    superpageOnly:           Boolean
  ): Bool = {
    if (superpage && usingVM) {
      var tagMatch = tlbEntry.valid.head
      for (j <- 0 until tlbEntry.pgLevels) {
        val base = (tlbEntry.pgLevels - 1 - j) * pgLevelBits
        val n = pgLevelBits + (if (j == 0) hypervisorExtraAddrBits else 0)
        val ignore = tlbEntry.level < j.U || (superpageOnly && (j == (tlbEntry.pgLevels - 1))).B
        tagMatch = tagMatch && (ignore || (tlbEntry.tag_vpn ^ vpn)(base + n - 1, base) === 0.U)
      }
      tagMatch
    } else {
      val idx = sectorIdx(tlbEntry, vpn)
      tlbEntry.valid(idx) && sectorTagMatch(tlbEntry, vpn)
    }
  }

  /** returns the ppn of the input TLBEntryData */
  def ppn(
    tlbEntry:      TLBEntry,
    vpn:           UInt,
    data:          TLBEntryData,
    usingVM:       Boolean,
    pgLevelBits:   Int,
    superpage:     Boolean,
    superpageOnly: Boolean
  ) = {
    val supervisorVPNBits = tlbEntry.pgLevels * pgLevelBits
    if (superpage && usingVM) {
      var res = data.ppn >> pgLevelBits * (tlbEntry.pgLevels - 1)
      for (j <- 1 until tlbEntry.pgLevels) {
        val ignore = tlbEntry.level < j.U || (superpageOnly && j == tlbEntry.pgLevels - 1).B
        res = Cat(
          res,
          (Mux(ignore, vpn, 0.U) | data.ppn)(
            supervisorVPNBits - j * pgLevelBits - 1,
            supervisorVPNBits - (j + 1) * pgLevelBits
          )
        )
      }
      res
    } else {
      data.ppn
    }
  }

  /** does the refill
    *
    * find the target entry with vpn tag and replace the target entry with the input entry data
    */
  def insert(tlbEntry: TLBEntry, vpn: UInt, level: UInt, entry: TLBEntryData, superpageOnly: Boolean): Unit = {
    tlbEntry.tag_vpn := vpn
    tlbEntry.level := level(log2Ceil(tlbEntry.pgLevels - (if (superpageOnly) 1 else 0)) - 1, 0)

    val idx = sectorIdx(tlbEntry, vpn)
    tlbEntry.valid(idx) := true.B
    tlbEntry.data(idx) := entry.asUInt
  }

  def invalidate(tlbEntry: TLBEntry): Unit = { tlbEntry.valid.foreach(_ := false.B) }
  def invalidateVPN(
    tlbEntry:                TLBEntry,
    vpn:                     UInt,
    usingVM:                 Boolean,
    pgLevelBits:             Int,
    hypervisorExtraAddrBits: Int,
    superpage:               Boolean,
    superpageOnly:           Boolean
  ): Unit = {
    if (superpage) {
      when(hit(tlbEntry, vpn, usingVM, pgLevelBits, hypervisorExtraAddrBits, superpage, superpageOnly)) {
        invalidate(tlbEntry)
      }
    } else {
      when(sectorTagMatch(tlbEntry, vpn)) {
        for (((v, e), i) <- (tlbEntry.valid.zip(entry_data(tlbEntry))).zipWithIndex)
          when(i.U === sectorIdx(tlbEntry, vpn)) { v := false.B }
      }
    }
    // For fragmented superpage mappings, we assume the worst (largest)
    // case, and zap entries whose most-significant VPNs match
    when(((tlbEntry.tag_vpn ^ vpn) >> (pgLevelBits * (tlbEntry.pgLevels - 1))) === 0.U) {
      for ((v, e) <- tlbEntry.valid.zip(entry_data(tlbEntry)))
        when(e.fragmented_superpage) { v := false.B }
    }
  }
  def invalidateNonGlobal(tlbEntry: TLBEntry): Unit = {
    for ((v, e) <- tlbEntry.valid.zip(entry_data(tlbEntry)))
      when(!e.g) { v := false.B }
  }
}

class TLBEntry(val nSectors: Int, val pgLevels: Int, vpnBits: Int, val ppnBits: Int) extends Bundle {
  val level = UInt(log2Ceil(pgLevels).W)
  val tag_vpn = UInt(vpnBits.W)
  val data = Vec(nSectors, UInt(new TLBEntryData(ppnBits).getWidth.W))
  val valid = Vec(nSectors, Bool())
}

class TLBEntryData(ppnBits: Int) extends Bundle {
  val ppn = UInt(ppnBits.W)

  /** access exception. D$ -> PTW -> TLB AE Alignment failed.
    */
  val ae_ptw = Bool()
  val ae_final = Bool()
  val ae_stage2 = Bool()

  /** pte.g global */
  val g = Bool()
  val pf = Bool() // page fault
  val pw = Bool() // write permission
  val px = Bool() // execute permission
  val pr = Bool() // read permission
  val c = Bool() // cacheable
  /** PutPartial */
  val ppp = Bool()

  /** AMO logical */
  val pal = Bool()

  /** AMO arithmetic */
  val paa = Bool()

  /** get/put effects */
  val eff = Bool()

  /** fragmented_superpage support */
  val fragmented_superpage = Bool()
}

class PTWReq(vpnBits: Int) extends Bundle {
  val addr = UInt(vpnBits.W)
  val stage2 = Bool()
}

/** PTE info from L2TLB to TLB
  *
  * containing: target PTE, exceptions, two-satge tanslation info
  */
class PTWResp(vaddrBits: Int, pgLevels: Int) extends Bundle {

  /** ptw access exception */
  val ae_ptw = Bool()

  /** final access exception */
  val ae_final = Bool()

  /** page fault */
  val pf = Bool()

  /** PTE to refill L1TLB
    *
    * source: L2TLB
    */
  val pte = new PTE

  /** pte pglevel */
  val level = UInt(log2Ceil(pgLevels).W)

  /** fragmented_superpage support */
  val fragmented_superpage = Bool()

  /** homogeneous for both pma and pmp */
  val homogeneous = Bool()
}

object PTE {

  /** return true if find a pointer to next level page table */
  def table(pte: PTE) =
    pte.v && !pte.r && !pte.w && !pte.x && !pte.d && !pte.a && !pte.u && pte.reserved_for_future === 0.U

  /** return true if find a leaf PTE */
  def leaf(pte: PTE) = pte.v && (pte.r || (pte.x && !pte.w)) && pte.a

  /** user read */
  def ur(pte: PTE) = leaf(pte) && pte.r

  /** user write */
  def uw(pte: PTE) = leaf(pte) && pte.w && pte.d

  /** user execute */
  def ux(pte: PTE) = leaf(pte) && pte.x

  /** full permission: writable and executable in user mode */
  def isFullPerm(pte: PTE) = uw(pte) && ux(pte)
}

/** PTE template for transmission
  *
  * contains useful methods to check PTE attributes
  * @see
  *   RV-priv spec 4.3.1 for pgae table entry format
  */
class PTE extends Bundle {
  val reserved_for_future = UInt(10.W)
  val ppn = UInt(44.W)
  val reserved_for_software = UInt(2.W)

  /** dirty bit */
  val d = Bool()

  /** access bit */
  val a = Bool()

  /** global mapping */
  val g = Bool()

  /** user mode accessible */
  val u = Bool()

  /** whether the page is executable */
  val x = Bool()

  /** whether the page is writable */
  val w = Bool()

  /** whether the page is readable */
  val r = Bool()

  /** valid bit */
  val v = Bool()
}

object PTBR {
  def additionalPgLevels(ptbr: PTBR, pgLevels: Int, minPgLevels: Int) =
    ptbr.mode(log2Ceil(pgLevels - minPgLevels + 1) - 1, 0)
  def modeBits(xLen: Int) = xLen match {
    case 32 => 1
    case 64 => 4
  }
  def maxASIdBits(xLen: Int) = xLen match {
    case 32 => 9
    case 64 => 16
  }
}

class PTBR(xLen: Int, maxPAddrBits: Int, pgIdxBits: Int) extends Bundle {
  val mode: UInt = UInt(PTBR.modeBits(xLen).W)
  val asid = UInt(PTBR.maxASIdBits(xLen).W)
  val ppn = UInt((maxPAddrBits - pgIdxBits).W)
}

class BTBReq(vaddrBits: Int) extends Bundle {
  val addr = UInt(vaddrBits.W)
}

class BTBResp(
  vaddrBits:        Int,
  entries:          Int,
  fetchWidth:       Int,
  bhtHistoryLength: Option[Int],
  bhtCounterLength: Option[Int])
    extends Bundle {

  val cfiType = UInt(CFIType.width.W)
  val taken = Bool()
  val mask = UInt(fetchWidth.W)
  val bridx = UInt(log2Ceil(fetchWidth).W)
  val target = UInt(vaddrBits.W)
  val entry = UInt(log2Ceil(entries + 1).W)
  val bht = new BHTResp(bhtHistoryLength, bhtCounterLength)
}

object BHTResp {
  def taken(bht:              BHTResp): Bool = bht.value(0)
  def strongly_taken(bhtResp: BHTResp): Bool = bhtResp.value === 1.U
}

class BHTResp(bhtHistoryLength: Option[Int], bhtCounterLength: Option[Int]) extends Bundle {
  val history = UInt(bhtHistoryLength.getOrElse(1).W)
  val value = UInt(bhtCounterLength.getOrElse(1).W)

  // @todo: change to:
  //  val history = bhtHistoryLength.map(i => UInt(i.W))
  //  val value = bhtCounterLength.map(i => UInt(i.W))
}

class BTBUpdate(
  vaddrBits:        Int,
  entries:          Int,
  fetchWidth:       Int,
  bhtHistoryLength: Option[Int],
  bhtCounterLength: Option[Int])
    extends Bundle {
  def fetchWidth: Int = 1

  val prediction = new BTBResp(vaddrBits, entries, fetchWidth, bhtHistoryLength, bhtCounterLength)
  val pc = UInt(vaddrBits.W)
  val target = UInt(vaddrBits.W)
  val taken = Bool()
  val isValid = Bool()
  val br_pc = UInt(vaddrBits.W)
  val cfiType = UInt(CFIType.width.W)
}

class BHTUpdate(bhtHistoryLength: Option[Int], bhtCounterLength: Option[Int], vaddrBits: Int) extends Bundle {
  val prediction = new BHTResp(bhtHistoryLength, bhtCounterLength)
  val pc = UInt(vaddrBits.W)
  val branch = Bool()
  val taken = Bool()
  val mispredict = Bool()
}

class RASUpdate(vaddrBits: Int) extends Bundle {
  val cfiType = UInt(CFIType.width.W)
  val returnAddr = UInt(vaddrBits.W)
}

// TODO: make it Enum
object CFIType {
  def width = 2
  def branch = 0.U
  def jump = 1.U
  def call = 2.U
  def ret = 3.U
}

class FrontendResp(
  warpNum:           Int,
  vaddrBits:         Int,
  vaddrBitsExtended: Int,
  coreInstBits:      Int,
  fetchWidth:        Int)
    extends Bundle {
  val pc = UInt(vaddrBitsExtended.W) // ID stage PC
  val wid = UInt(log2Ceil(warpNum).W)
  val data = UInt((fetchWidth * coreInstBits).W)
  val mask = UInt(fetchWidth.W)
  val xcpt = new FrontendExceptions
}

class FrontendExceptions extends Bundle {
  val pf = Bool()
  val gf = Bool()
  val ae = Bool()
}

class FrontendReq(warpNum: Int, vaddrBitsExtended: Int) extends Bundle {
  val wid = UInt(log2Ceil(warpNum).W)
  val pc = UInt(vaddrBitsExtended.W)
  // val speculative = Bool()
}

class FrontendPerfEvents extends Bundle {
  val acquire = Bool()
  val tlbMiss = Bool()
}

class FrontendIO(
  warpNum:           Int, // Number of warps
  vaddrBitsExtended: Int,
  vaddrBits:         Int,
  asidBits:          Int,
  coreInstBits:      Int,
  fetchWidth:        Int)
    extends Bundle {
  val might_request = Output(Bool())
  val clock_enabled = Input(Bool())
  val req = Decoupled(new FrontendReq(warpNum, vaddrBitsExtended))
  val sfence = Valid(new SFenceReq(vaddrBits, asidBits))
  val resp = Flipped(
    Decoupled(
      new FrontendResp(
        warpNum,
        vaddrBits,
        vaddrBitsExtended,
        coreInstBits,
        fetchWidth
      )
    )
  )
  val gpa = Flipped(Valid(UInt(vaddrBitsExtended.W)))
  val ras_update = Valid(new RASUpdate(vaddrBits))
  val flush_icache = Output(Bool())
  val npc = Input(UInt(vaddrBitsExtended.W))
  val perf = Input(new FrontendPerfEvents)
  val progress = Output(Bool())
}

// Non-diplomatic version of Frontend
class FrontendBundle(
  warpNum:           Int, // Number of warps
  vaddrBitsExtended: Int,
  vaddrBits:         Int,
  asidBits:          Int,
  coreInstBits:      Int,
  vpnBits:           Int,
  paddrBits:         Int,
  pgLevels:          Int,
  xLen:              Int,
  maxPAddrBits:      Int,
  pgIdxBits:         Int,
  hasCorrectable:    Boolean,
  hasUncorrectable:  Boolean,
  fetchWidth:        Int)
    extends Bundle {
  val cpu = Flipped(
    new FrontendIO(
      warpNum,
      vaddrBitsExtended,
      vaddrBits,
      asidBits,
      coreInstBits,
      fetchWidth
    )
  )
  val ptw = new TLBPTWIO(vpnBits, paddrBits, vaddrBits, pgLevels, xLen, maxPAddrBits, pgIdxBits)
  val errors = new ICacheErrors(hasCorrectable, hasUncorrectable, paddrBits)
}

// ALU operand bundle for use in ALU, Issue, and other modules
class ALUOperandBundle(parameter: OGPUDecoderParameter) extends Bundle {
  val warpID = UInt(log2Ceil(parameter.warpNum).W)
  val execType = UInt(2.W)
  val aluFn = UInt(parameter.UOPALU.width.W)
  val funct3 = UInt(3.W)
  val funct7 = UInt(7.W)
  val pc = UInt(parameter.xLen.W)
  val rs1Data = UInt(parameter.xLen.W)
  val rs2Data = UInt(parameter.xLen.W)
  val imm = UInt(parameter.xLen.W)
  val rd = UInt(5.W)
  val isRVC = Bool()
  val branch = new Bundle {
    val isJal = Bool()
    val isJalr = Bool()
    val isBranch = Bool()
  }
}

// Result bundle for use in ALU, FPU, Issue, and other modules
class ResultBundle(parameter: OGPUDecoderParameter) extends Bundle {
  val result = UInt(parameter.xLen.W)
  val warpID = UInt(log2Ceil(parameter.warpNum).W)
  val rd = UInt(5.W)
  val exception = Bool()
  val fflags = UInt(5.W) // For FPU, default 0 for ALU
}

// FPU operand bundle for use in FPU, Issue, and other modules
class FPUOperandBundle(parameter: OGPUDecoderParameter) extends Bundle {
  val warpID = UInt(log2Ceil(parameter.warpNum).W)
  val pc = UInt(parameter.xLen.W)
  val rs1Data = UInt(parameter.xLen.W)
  val rs2Data = UInt(parameter.xLen.W)
  val rs3Data = UInt(parameter.xLen.W)
  val rd = UInt(5.W)
  // val isRVC = Bool()
  val rnd_mode = UInt(3.W)
  val op = UInt(5.W)
  val op_mod = Bool()
  val src_fmt = UInt(2.W)
  val dst_fmt = UInt(2.W)
  val int_fmt = UInt(2.W)
  val vectorial_op = Bool()
  val tag_i = UInt(5.W)
  val flush = Bool()
}

// Bundle for register file writeback
class RegFileWriteBundle(parameter: OGPUDecoderParameter) extends Bundle {
  val en = Bool()
  val warpID = UInt(log2Ceil(parameter.warpNum).W)
  val addr = UInt(5.W)
  val data = UInt(parameter.xLen.W)
}

// Bundle for scoreboard clear
class ScoreboardClearBundle(parameter: WarpScoreboardParameter) extends Bundle {
  val en = Bool()
  val warpID = UInt(log2Ceil(parameter.warpNum).W)
  val addr = UInt(5.W)
}

// Bundle for scoreboard set
class ScoreboardSetBundle(parameter: WarpScoreboardParameter) extends Bundle {
  val en = Bool()
  val warpID = UInt(log2Ceil(parameter.warpNum).W)
  val addr = UInt(5.W)
}

// Branch info bundle for ALU branch output
class BranchInfoBundle(parameter: OGPUDecoderParameter) extends Bundle {
  val cmp_out = Bool() // ALU comparison result
  val warpID = UInt(log2Ceil(parameter.warpNum).W)
  val pc = UInt(parameter.xLen.W)
  val imm = UInt(parameter.xLen.W)
  val rs1Data = UInt(parameter.xLen.W)
  val isRVC = Bool()
  val branch = new Bundle {
    val isJal = Bool()
    val isJalr = Bool()
    val isBranch = Bool()
  }
}
