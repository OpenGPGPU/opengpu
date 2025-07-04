// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2024 Jiuyang Liu <liu@jiuyang.me>
package ogpu.core

import chisel3._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}
import chisel3.util.BitPat
import chisel3.util.experimental.decode.{BoolDecodeField, DecodeField, DecodeTable}
import org.chipsalliance.rvdecoderdb.{Encoding, Instruction, InstructionSet}

import org.chipsalliance.rocketv.{FPUHelper, RocketDecodePattern, UOP, UOPDecodeField}
import org.chipsalliance.t1.rtl.decoder.DecoderParam
import org.chipsalliance.rocketv.RVCExpanderParameter

object CustomInstructions {
  private def ogpu(name: String, encoding: Encoding) =
    Instruction(name, encoding, Seq(), Seq(InstructionSet("rv_ogpu")), None, false, true)

  val ogpuSet = Seq(
    // vector branch insts, reuse ventus-gpgpu insts
    ogpu("vbeq", Encoding.fromString("?????????????????000?????1011011")),
    ogpu("vbne", Encoding.fromString("?????????????????001?????1011011")),
    ogpu("vblt", Encoding.fromString("?????????????????100?????1011011")),
    ogpu("vbge", Encoding.fromString("?????????????????101?????1011011")),
    ogpu("vbltu", Encoding.fromString("?????????????????110?????1011011")),
    ogpu("vbgeu", Encoding.fromString("?????????????????111?????1011011")),

    // join
    ogpu("join", Encoding.fromString("?????????????????010?????1011011")),

    // warp ceases
    ogpu("cease", Encoding.fromString("00110000010100000000000001110011"))
  )
}

case class OGPUDecoderParameter(
  instructionSets:   Set[String],
  pipelinedMul:      Boolean,
  fenceIFlushDCache: Boolean,
  warpNum:           Int = 8,
  minFLen:           Int = 16,
  vLen:              Int = 1024,
  xLen:              Int = 32)
    extends SerializableModuleParameter {
  val instructions: Seq[Instruction] =
    ((org.chipsalliance.rvdecoderdb
      .instructions(
        org.chipsalliance.rvdecoderdb.extractResource(getClass.getClassLoader)
      )
      .filter(instruction =>
        (
          instructionSets ++
            // Four mandatory instruction sets.
            Seq("rv_i", "rv_zicsr", "rv_zifencei", "rv_system")
        ).contains(instruction.instructionSet.name)
      )
      .toSeq
      .filter {
        // special case for rv32 pseudo from rv64
        case i if i.pseudoFrom.isDefined && Seq("slli", "srli", "srai").contains(i.name) => true
        case i if i.pseudoFrom.isDefined                                                 => false
        case _                                                                           => true
      })
      ++ CustomInstructions.ogpuSet)
      .sortBy(i => (i.instructionSet.name, i.name))

  // functions below is my little reminder, which is used for future rocket core refactoring, just keep it, I'll remove it later in the future.
  private def hasAnySetIn(sets: String*): Boolean =
    sets.exists(set => instructions.flatMap(_.instructionSets.map(_.name)).exists(_.contains(set)))

  private def fLen0: Boolean = !fLen32 && !fLen64

  private def fLen32: Boolean = hasAnySetIn("rv_f", "rv32_f", "rv64_f")

  private def fLen64: Boolean = hasAnySetIn("rv_d", "rv32_d", "rv64_d")

  val useFPU = !fLen0
  private val useMulDiv = hasAnySetIn("rv_m", "rv64_m")
  val useVector = hasAnySetIn("rv_v")

  val rvc_decode_param = RVCExpanderParameter(
    xLen,
    false
  )

  val vector_decode_param = DecoderParam(
    true,
    true,
    instructions.filter(instruction => Set("rv_v").contains(instruction.instructionSet.name))
  )

  private val instructionDecodePatterns: Seq[RocketDecodePattern] = instructions.map(RocketDecodePattern.apply)
  private val instructionDecodeFields: Seq[DecodeField[RocketDecodePattern, _ <: Data]] = Seq(
    isLegal,
    isBranch,
    isVectorBranch,
    isJal,
    isJalr,
    isJoin,
    isCease,
    rxs2,
    rxs1,
    selAlu2,
    selAlu1,
    selImm,
    aluDoubleWords,
    mem,
    memCommand,
    wxd,
    csr,
    mul,
    div,
    fenceI,
    fence,
    amo,
    execType,
    aluFn
  ) ++
    (if (useFPU)
       Seq(
         fp,
         rfs1,
         rfs2,
         rfs3,
         wfd,
         dp,
         rnd_mode,
         op,
         op_mod,
         src_fmt,
         dst_fmt,
         int_fmt,
         vectorial_op,
         tag_i
       )
     else None) ++
    (if (useMulDiv) if (pipelinedMul) Seq(mul, div) else Seq(div) else None) ++
    (if (useVector) Seq(vector, vectorLSU, vectorCSR, vectorReadFRs1) else None)
  private val Y = BitPat.Y()
  private val N = BitPat.N()

  private val FPUDecodeFields: Seq[DecodeField[RocketDecodePattern, _ <: Data]] = Seq(
    fldst,
    fwen,
    fren1,
    fren2,
    fren3,
    fswap12,
    fswap23,
    ftypeTagIn,
    ftypeTagOut,
    ffromint,
    ftoint,
    ffastpipe,
    ffma,
    fdiv,
    fsqrt,
    fwflags,
    rnd_mode,
    op,
    op_mod,
    src_fmt,
    dst_fmt,
    int_fmt,
    vectorial_op,
    tag_i
  )

  val coreTable: DecodeTable[RocketDecodePattern] = new DecodeTable[RocketDecodePattern](
    instructionDecodePatterns,
    instructionDecodeFields
  )

  val floatTable: DecodeTable[RocketDecodePattern] = new DecodeTable[RocketDecodePattern](
    instructionDecodePatterns,
    FPUDecodeFields
  )

  // val ogpuTable: DecodeTable[RocketDecodePattern= new DecodeTable[RocketDecodePattern](
  //   instructionDecodePatterns,
  //   OGPUDecodeFields
  // )

  object isLegal extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "legal"

    override def default: BitPat = n

    // should always be true
    override def genTable(op: RocketDecodePattern): BitPat = y
  }

  object ExecutionType extends UOP {
    def width = 3
    def ALU:    BitPat = encode(0)
    def FPU:    BitPat = encode(1)
    def VEC:    BitPat = encode(2)
    def BRANCH: BitPat = encode(3)
    def MEM:    BitPat = encode(4)
    def CSR:    BitPat = encode(5)
    def SYSTEM: BitPat = encode(6)
  }

  object execType extends UOPDecodeField[RocketDecodePattern] {
    override def name: String = "execType"
    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.instructionSet.name match {
      case "rv_v" => ExecutionType.VEC
      case s
          if Seq("rv_f", "rv_d", "rv_q", "rv_zfh", "rv64_f", "rv64_d", "rv64_q", "rv64_zfh", "rv_d_zfh", "rv_q_zfh")
            .contains(s) =>
        ExecutionType.FPU
      case "rv_i" | "rv_m" =>
        op.instruction.name match {
          case "jal" | "jalr" | "beq" | "bne" | "blt" | "bge" | "bltu" | "bgeu" => ExecutionType.BRANCH
          case "lw" | "sw" | "lb" | "lh" | "lbu" | "lhu" | "sb" | "sh" | "ld" | "sd" | "lwu" | "sll" | "srl" | "sra" =>
            ExecutionType.MEM
          case "csrrw" | "csrrs" | "csrrc" | "csrrwi" | "csrrsi" | "csrrci"       => ExecutionType.CSR
          case "ecall" | "ebreak" | "mret" | "sret" | "wfi" | "fence" | "fence.i" => ExecutionType.SYSTEM
          case _                                                                  => ExecutionType.ALU
        }
      case _ => ExecutionType.ALU
    }
    override def uopType: ExecutionType.type = ExecutionType
  }
  object fp extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "fp"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.instructionSet.name match {
      // format: off
      case s if Seq(
        "rv_d", "rv64_d",
        "rv_f", "rv64_f",
        "rv_q", "rv64_q",
        "rv_zfh", "rv64_zfh", "rv_d_zfh", "rv_q_zfh",
      ).contains(s) => y
      case _ => n
      // format: on
    }
  }

  object dp extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "dp"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.instructionSet.name match {
      // format: off
      case s if Seq("rv_d", "rv_d_zfh", "rv64_d").contains(s) => y
      case _ => n
      // format: on
    }
  }

  object isBranch extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "branch"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      // format: off
      case i if Seq("bne", "beq", "blt", "bltu", "bge", "bgeu").contains(i) => y
      case _ => n
      // format: on
    }
  }

  object isVectorBranch extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "vectorbranch"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      // format: off
      case i if Seq("vbne", "vbeq", "vblt", "vbltu", "vbge", "vbgeu").contains(i) => y
      case _ => n
      // format: on
    }
  }

  object isJal extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "jal"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      // format: off
      case i if Seq("jal").contains(i) => y
      case _ => n
      // format: on
    }
  }

  object isJalr extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "jalr"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      // format: off
      case i if Seq("jalr").contains(i) => y
      case _ => n
      // format: on
    }
  }

  object isJoin extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "join"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      // format: off
      case i if Seq("join").contains(i) => y
      case _ => n
      // format: on
    }
  }

  object isCease extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "cease"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      // format: off
      case i if Seq("cease").contains(i) => y
      case _ => n
      // format: on
    }
  }

  object rxs2 extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "rxs2"

    override def genTable(op: RocketDecodePattern): BitPat = (op.instruction.name, op) match {
      // format: off
      case (i, _) if Seq("amomaxu.w", "amoand.w", "amoor.w", "amoxor.w", "amoswap.w", "lr.w", "amomax.w", "amoadd.w", "amomin.w", "amominu.w", "sc.w", "lr.d", "amomax.d", "amoswap.d", "amoxor.d", "amoand.d", "amomin.d", "amoor.d", "amoadd.d", "amomaxu.d", "amominu.d", "sc.d", "hsv.w", "hsv.b", "hfence.vvma", "hsv.h", "hfence.gvma", "hsv.d", "or", "srl", "sltu", "sra", "sb", "add", "xor", "beq", "bge", "sw", "blt", "bgeu", "bltu", "bne", "sub", "and", "slt", "sh", "sll", "addw", "sd", "sllw", "sraw", "subw", "srlw", "mulhsu", "rem", "div", "mul", "mulhu", "mulh", "remu", "divu", "remuw", "divw", "divuw", "mulw", "remw", "sfence.vma", "czero.nez", "czero.eqz").contains(i) => y
      case (_, p) if p.vectorReadRs2 => y
      case _ => n
      // format: on
    }
  }

  object rxs1 extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "rxs1"

    override def genTable(op: RocketDecodePattern): BitPat = (op.instruction.name, op) match {
      // format: off
      case (i, _) if Seq("amomaxu.w", "amoand.w", "amoor.w", "amoxor.w", "amoswap.w", "lr.w", "amomax.w", "amoadd.w", "amomin.w", "amominu.w", "sc.w", "lr.d", "amomax.d", "amoswap.d", "amoxor.d", "amoand.d", "amomin.d", "amoor.d", "amoadd.d", "amomaxu.d", "amominu.d", "sc.d", "fld", "fcvt.d.wu", "fsd", "fcvt.d.w", "fcvt.d.lu", "fmv.d.x", "fcvt.d.l", "fcvt.s.wu", "fmv.w.x", "fsw", "fcvt.s.w", "flw", "fcvt.s.lu", "fcvt.s.l", "hsv.w", "hsv.b", "hfence.vvma", "hlv.hu", "hlvx.hu", "hlv.b", "hlvx.wu", "hlv.w", "hsv.h", "hlv.h", "hlv.bu", "hfence.gvma", "hsv.d", "hlv.d", "hlv.wu", "or", "srl", "ori", "lhu", "sltu", "sra", "sb", "lw", "add", "xor", "beq", "andi", "bge", "sw", "blt", "bgeu", "sltiu", "lh", "bltu", "jalr", "bne", "lbu", "sub", "and", "xori", "slti", "slt", "addi", "lb", "sh", "sll", "srli", "srai", "slli", "ld", "addw", "sd", "sraiw", "lwu", "sllw", "sraw", "subw", "srlw", "addiw", "srliw", "slliw", "mulhsu", "rem", "div", "mul", "mulhu", "mulh", "remu", "divu", "remuw", "divw", "divuw", "mulw", "remw", "sfence.vma", "fsh", "flh", "fcvt.h.wu", "fcvt.h.w", "fmv.h.x", "fcvt.h.lu", "fcvt.h.l", "csrrc", "csrrs", "csrrw", "czero.nez", "czero.eqz", "cflush.d.l1", "cdiscard.d.l1").contains(i) => y
      case (i, _) if Seq("ecall", "ebreak", "mret", "wfi", "sret", "dret", "nmret").contains(i) => dc
      case (_, p) if p.vectorReadRs1 => y
      case _                                                                                        => n
      // format: on
    }
  }

  object fenceI extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "fence_i"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      // format: off
      case i if Seq("fence.i").contains(i) => y
      case _ => n
      // format: on
    }
  }

  object fence extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "fence"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      // format: off
      case i if Seq("fence").contains(i) => y
      case _ => n
      // format: on
    }
  }

  object amo extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "amo"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.instructionSet.name match {
      // format: off
      case s if Seq("rv_a", "rv64_a").contains(s) => y
      case _ => n
      // format: on
    }
  }

  object aluDoubleWords extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "alu_dw"

    override def genTable(op: RocketDecodePattern): BitPat = {
      op.instruction.name match {
        // format: off
        case i if Seq("amomaxu.w", "amoand.w", "amoor.w", "amoxor.w", "amoswap.w", "lr.w", "amomax.w", "amoadd.w", "amomin.w", "amominu.w", "sc.w", "lr.d", "amomax.d", "amoswap.d", "amoxor.d", "amoand.d", "amomin.d", "amoor.d", "amoadd.d", "amomaxu.d", "amominu.d", "sc.d", "fld", "fsd", "fsw", "flw", "hsv.w", "hsv.b", "hfence.vvma", "hlv.hu", "hlvx.hu", "hlv.b", "hlvx.wu", "hlv.w", "hsv.h", "hlv.h", "hlv.bu", "hfence.gvma", "hsv.d", "hlv.d", "hlv.wu", "or", "srl", "ori", "lhu", "sltu", "sra", "sb", "lw", "add", "xor", "beq", "andi", "bge", "sw", "blt", "bgeu", "sltiu", "lh", "bltu", "jalr", "lui", "bne", "lbu", "sub", "and", "auipc", "xori", "slti", "slt", "addi", "lb", "jal", "sh", "sll", "srli", "srai", "slli", "ld", "sd", "lwu", "mulhsu", "rem", "div", "mul", "mulhu", "mulh", "remu", "divu", "sfence.vma", "fsh", "flh", "csrrc", "csrrci", "csrrs", "csrrw", "csrrsi", "csrrwi", "czero.nez", "czero.eqz").contains(i) => y
        case i if Seq("addw", "sraiw", "sllw", "sraw", "subw", "srlw", "addiw", "srliw", "slliw", "remuw", "divw", "divuw", "mulw", "remw").contains(i) => n
        case _ => dc
        // format: on
      }
    }
  }

  object mem extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "mem"

    override def default: BitPat = n

    override def genTable(op: RocketDecodePattern): BitPat = {
      op.instruction.name match {
        // format: off
        case i if Seq("amomaxu.w", "amoand.w", "amoor.w", "amoxor.w", "amoswap.w", "lr.w", "amomax.w", "amoadd.w", "amomin.w", "amominu.w", "sc.w", "lr.d", "amomax.d", "amoswap.d", "amoxor.d", "amoand.d", "amomin.d", "amoor.d", "amoadd.d", "amomaxu.d", "amominu.d", "sc.d", "fld", "fsd", "fsw", "flw", "hsv.w", "hsv.b", "hlv.hu", "hlv.b", "hlv.w", "hsv.h", "hlv.h", "hlv.bu", "hsv.d", "hlv.d", "hlv.wu", "lhu", "sb", "lw", "sw", "lh", "lbu", "lb", "sh", "ld", "sd", "lwu", "sfence.vma", "fsh", "flh").contains(i) => y
        case i if Seq("fence.i").contains(i) && fenceIFlushDCache => y
        case _ => n
        // format: on
      }
    }
  }

  object rfs1 extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "rfs1"

    override def genTable(op: RocketDecodePattern): BitPat = {
      op.instruction.name match {
        // format: off
        case i if Seq("fmin.d", "fsgnj.d", "fle.d", "fnmsub.d", "fadd.d", "fcvt.w.d", "fmsub.d", "fmul.d", "fcvt.wu.d", "feq.d", "fmax.d", "fnmadd.d", "fcvt.d.s", "fcvt.s.d", "fmadd.d", "fsgnjx.d", "flt.d", "fsgnjn.d", "fsub.d", "fsqrt.d", "fclass.d", "fdiv.d", "fmv.x.d", "fcvt.lu.d", "fcvt.l.d", "fcvt.d.h", "fcvt.h.d", "fnmsub.s", "fsgnjx.s", "fmsub.s", "fsgnjn.s", "fdiv.s", "fmin.s", "fsqrt.s", "fclass.s", "fcvt.wu.s", "fmax.s", "feq.s", "fle.s", "fmadd.s", "fsgnj.s", "fadd.s", "flt.s", "fmv.x.w", "fnmadd.s", "fmul.s", "fcvt.w.s", "fsub.s", "fcvt.lu.s", "fcvt.l.s", "feq.h", "fsgnjx.h", "fcvt.w.h", "fcvt.h.s", "fdiv.h", "fclass.h", "fsgnj.h", "fmul.h", "fsub.h", "fcvt.wu.h", "fadd.h", "fmax.h", "fsgnjn.h", "fmv.x.h", "fcvt.s.h", "fmsub.h", "fmin.h", "fsqrt.h", "flt.h", "fnmadd.h", "fmadd.h", "fnmsub.h", "fle.h", "fcvt.l.h", "fcvt.lu.h").contains(i) => y
        case _ => n
        // format: on
      }
    }
  }

  object rfs2 extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "rfs2"

    override def genTable(op: RocketDecodePattern): BitPat = {
      op.instruction.name match {
        // format: off
        case i if Seq("fmin.d", "fsgnj.d", "fle.d", "fnmsub.d", "fadd.d", "fmsub.d", "fmul.d", "feq.d", "fmax.d", "fnmadd.d", "fmadd.d", "fsgnjx.d", "flt.d", "fsgnjn.d", "fsub.d", "fsqrt.d", "fdiv.d", "fnmsub.s", "fsgnjx.s", "fmsub.s", "fsgnjn.s", "fdiv.s", "fmin.s", "fsqrt.s", "fmax.s", "feq.s", "fle.s", "fmadd.s", "fsgnj.s", "fadd.s", "flt.s", "fnmadd.s", "fmul.s", "fsub.s", "feq.h", "fsgnjx.h", "fdiv.h", "fsgnj.h", "fmul.h", "fsub.h", "fadd.h", "fmax.h", "fsgnjn.h", "fmsub.h", "fmin.h", "fsqrt.h", "flt.h", "fnmadd.h", "fmadd.h", "fnmsub.h", "fle.h").contains(i) => y
        case _ => n
        // format: on
      }
    }
  }

  object rfs3 extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "rfs3"

    override def genTable(op: RocketDecodePattern): BitPat =
      op.instruction.name match {
        // format: off
        case i if Seq("fnmsub.d", "fmsub.d", "fnmadd.d", "fmadd.d", "fnmsub.s", "fmsub.s", "fmadd.s", "fnmadd.s", "fmsub.h", "fnmadd.h", "fmadd.h", "fnmsub.h").contains(i) => y
        case _ => n
        // format: on
      }
  }

  object wfd extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "wfd"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      // format: off
      case i if Seq("fmin.d", "fsgnj.d", "fnmsub.d", "fadd.d", "fmsub.d", "fld", "fmul.d", "fmax.d", "fcvt.d.wu", "fnmadd.d", "fcvt.d.s", "fcvt.s.d", "fsd", "fmadd.d", "fsgnjx.d", "fsgnjn.d", "fsub.d", "fsqrt.d", "fcvt.d.w", "fdiv.d", "fcvt.d.lu", "fmv.d.x", "fcvt.d.l", "fcvt.d.h", "fcvt.h.d", "fnmsub.s", "fsgnjx.s", "fmsub.s", "fsgnjn.s", "fdiv.s", "fmin.s", "fsqrt.s", "fmax.s", "fcvt.s.wu", "fmv.w.x", "fmadd.s", "fsgnj.s", "fadd.s", "fsw", "fnmadd.s", "fcvt.s.w", "flw", "fmul.s", "fsub.s", "fcvt.s.lu", "fcvt.s.l", "fsgnjx.h", "fcvt.h.s", "fdiv.h", "fsgnj.h", "fmul.h", "fsub.h", "flh", "fadd.h", "fmax.h", "fsgnjn.h", "fcvt.s.h", "fcvt.h.wu", "fcvt.h.w", "fmsub.h", "fmin.h", "fsqrt.h", "fnmadd.h", "fmadd.h", "fnmsub.h", "fmv.h.x", "fcvt.h.lu", "fcvt.h.l").contains(i) => y
      case i if Seq("vfmv.f.s").contains(i) => y
      case _ => n
      // format: on
    }
  }

  object mul extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "mul"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      // format: off
      case i if Seq("mulhsu", "mul", "mulhu", "mulh", "mulw").contains(i) => y
      case _ => n
      // format: on
    }
  }

  object div extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "div"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      // format: off
      case i if Seq("mulhsu", "mul", "mulhu", "mulh", "mulw").contains(i) && !pipelinedMul => y
      case i if Seq("rem", "div", "remu", "divu", "remuw", "divw", "divuw", "remw").contains(i) => y
      case _ => n
      // format: on
    }
  }

  object wxd extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "wxd"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      // format: off
      // TODO: filter out rd
      case i if Seq("amomaxu.w", "amoand.w", "amoor.w", "amoxor.w", "amoswap.w", "lr.w", "amomax.w", "amoadd.w", "amomin.w", "amominu.w", "sc.w", "lr.d", "amomax.d", "amoswap.d", "amoxor.d", "amoand.d", "amomin.d", "amoor.d", "amoadd.d", "amomaxu.d", "amominu.d", "sc.d", "fle.d", "fcvt.w.d", "fcvt.wu.d", "feq.d", "flt.d", "fclass.d", "fmv.x.d", "fcvt.lu.d", "fcvt.l.d", "fclass.s", "fcvt.wu.s", "feq.s", "fle.s", "flt.s", "fmv.x.w", "fcvt.w.s", "fcvt.lu.s", "fcvt.l.s", "hlv.hu", "hlvx.hu", "hlv.b", "hlvx.wu", "hlv.w", "hlv.h", "hlv.bu", "hlv.d", "hlv.wu", "or", "srl", "ori", "lhu", "sltu", "sra", "lw", "add", "xor", "andi", "sltiu", "lh", "jalr", "lui", "lbu", "sub", "and", "auipc", "xori", "slti", "slt", "addi", "lb", "jal", "sll", "srli", "srai", "slli", "ld", "addw", "sraiw", "lwu", "sllw", "sraw", "subw", "srlw", "addiw", "srliw", "slliw", "mulhsu", "rem", "div", "mul", "mulhu", "mulh", "remu", "divu", "remuw", "divw", "divuw", "mulw", "remw", "feq.h", "fcvt.w.h", "fclass.h", "fcvt.wu.h", "fmv.x.h", "flt.h", "fle.h", "fcvt.l.h", "fcvt.lu.h", "csrrc", "csrrci", "csrrs", "csrrw", "csrrsi", "csrrwi", "czero.nez", "czero.eqz").contains(i) => y
      case i if Seq("vsetvl", "vsetivli", "vsetvli", "vmv.x.s", "vcpop.m", "vfirst.m").contains(i) => y
      case _ => n
      // format: on
    }
  }

  // UOPs

  object UOPMEM extends UOP {
    def width = 5

    def xrd: BitPat = encode("b00000")

    def xwr: BitPat = encode("b00001")

    def pfr: BitPat = encode("b00010")

    def pfw: BitPat = encode("b00011")

    def xaSwap: BitPat = encode("b00100")

    def flushAll: BitPat = encode("b00101")

    def xlr: BitPat = encode("b00110")

    def xsc: BitPat = encode("b00111")

    def xaAdd: BitPat = encode("b01000")

    def xaXor: BitPat = encode("b01001")

    def xaOr: BitPat = encode("b01010")

    def xaAnd: BitPat = encode("b01011")

    def xaMin: BitPat = encode("b01100")

    def xaMax: BitPat = encode("b01101")

    def xaMinu: BitPat = encode("b01110")

    def xaMaxu: BitPat = encode("b01111")

    // TODO: unused
    def flush: BitPat = encode("b10000")

    // TODO: unused
    def pwr: BitPat = encode("b10001")

    // TODO: unused
    def produce: BitPat = encode("b10010")

    // TODO: unused
    def clean: BitPat = encode("b10011")

    def sfence: BitPat = encode("b10100")

    def hfencev: BitPat = encode("b10101")

    def hfenceg: BitPat = encode("b10110")

    def wok: BitPat = encode("b10111")

    def hlvx: BitPat = encode("b10000")
  }

  object memCommand extends UOPDecodeField[RocketDecodePattern] {
    override def name: String = "mem_cmd"

    override def genTable(op: RocketDecodePattern): BitPat = {
      op.instruction.name match {
        // format: off
        case i if Seq("fld", "flh", "flw", "hlv.b", "hlv.bu", "hlv.d", "hlv.h", "hlv.hu", "hlv.w", "hlv.wu", "lb", "lbu", "ld", "lh", "lhu", "lw", "lwu").contains(i) => UOPMEM.xrd
        case i if Seq("fsd", "fsh", "fsw", "hsv.b", "hsv.d", "hsv.h", "hsv.w", "sb", "sd", "sh", "sw").contains(i) => UOPMEM.xwr
        case i if Seq("amoswap.d", "amoswap.w").contains(i) => UOPMEM.xaSwap
        case i if Seq("fence.i").contains(i) && fenceIFlushDCache => UOPMEM.flushAll
        case i if Seq("lr.d", "lr.w").contains(i) => UOPMEM.xlr
        case i if Seq("sc.d", "sc.w").contains(i) => UOPMEM.xsc
        case i if Seq("amoadd.d", "amoadd.w").contains(i) => UOPMEM.xaAdd
        case i if Seq("amoxor.d", "amoxor.w").contains(i) => UOPMEM.xaXor
        case i if Seq("amoor.d", "amoor.w").contains(i) => UOPMEM.xaOr
        case i if Seq("amoand.d", "amoand.w").contains(i) => UOPMEM.xaAnd
        case i if Seq("amomin.d", "amomin.w").contains(i) => UOPMEM.xaMin
        case i if Seq("amomax.d", "amomax.w").contains(i) => UOPMEM.xaMax
        case i if Seq("amominu.d", "amominu.w").contains(i) => UOPMEM.xaMinu
        case i if Seq("amomaxu.d", "amomaxu.w").contains(i) => UOPMEM.xaMaxu
        case i if Seq("sfence.vma").contains(i) => UOPMEM.sfence
        case i if Seq("hfence.vvma").contains(i) => UOPMEM.hfencev
        case i if Seq("hfence.gvma").contains(i) => UOPMEM.hfenceg
        case i if Seq("hlvx.hu", "hlvx.wu").contains(i) => UOPMEM.hlvx
        case _ => UOPMEM.dontCare
        // format: on
      }
    }

    override def uopType: UOPMEM.type = UOPMEM
  }

  object UOPCSR extends UOP {
    def width = 3

    def n: BitPat = encode(0)

    def r: BitPat = encode(2)

    def i: BitPat = encode(4)

    def w: BitPat = encode(5)

    def s: BitPat = encode(6)

    def c: BitPat = encode(7)
  }

  object csr extends UOPDecodeField[RocketDecodePattern] {
    override def name: String = "csr"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      // format: off
      // TODO: default should be N?
      case i if Seq("amomaxu.w", "amoand.w", "amoor.w", "amoxor.w", "amoswap.w", "lr.w", "amomax.w", "amoadd.w", "amomin.w", "amominu.w", "sc.w", "lr.d", "amomax.d", "amoswap.d", "amoxor.d", "amoand.d", "amomin.d", "amoor.d", "amoadd.d", "amomaxu.d", "amominu.d", "sc.d", "fmin.d", "fsgnj.d", "fle.d", "fnmsub.d", "fadd.d", "fcvt.w.d", "fmsub.d", "fld", "fmul.d", "fcvt.wu.d", "feq.d", "fmax.d", "fcvt.d.wu", "fnmadd.d", "fcvt.d.s", "fcvt.s.d", "fsd", "fmadd.d", "fsgnjx.d", "flt.d", "fsgnjn.d", "fsub.d", "fsqrt.d", "fclass.d", "fcvt.d.w", "fdiv.d", "fcvt.d.lu", "fmv.x.d", "fmv.d.x", "fcvt.lu.d", "fcvt.l.d", "fcvt.d.l", "fcvt.d.h", "fcvt.h.d", "fnmsub.s", "fsgnjx.s", "fmsub.s", "fsgnjn.s", "fdiv.s", "fmin.s", "fsqrt.s", "fclass.s", "fcvt.wu.s", "fmax.s", "feq.s", "fcvt.s.wu", "fmv.w.x", "fle.s", "fmadd.s", "fsgnj.s", "fadd.s", "fsw", "flt.s", "fmv.x.w", "fnmadd.s", "fcvt.s.w", "flw", "fmul.s", "fcvt.w.s", "fsub.s", "fcvt.lu.s", "fcvt.s.lu", "fcvt.l.s", "fcvt.s.l", "or", "srl", "fence", "ori", "lhu", "sltu", "sra", "sb", "lw", "add", "xor", "beq", "andi", "bge", "sw", "blt", "bgeu", "sltiu", "lh", "bltu", "jalr", "lui", "bne", "lbu", "sub", "and", "auipc", "xori", "slti", "slt", "addi", "lb", "jal", "sh", "sll", "srli", "srai", "slli", "ld", "addw", "sd", "sraiw", "lwu", "sllw", "sraw", "subw", "srlw", "addiw", "srliw", "slliw", "mulhsu", "rem", "div", "mul", "mulhu", "mulh", "remu", "divu", "remuw", "divw", "divuw", "mulw", "remw", "feq.h", "fsgnjx.h", "fcvt.w.h", "fcvt.h.s", "fdiv.h", "fclass.h", "fsh", "fsgnj.h", "fmul.h", "fsub.h", "flh", "fcvt.wu.h", "fadd.h", "fmax.h", "fsgnjn.h", "fmv.x.h", "fcvt.s.h", "fcvt.h.wu", "fcvt.h.w", "fmsub.h", "fmin.h", "fsqrt.h", "flt.h", "fnmadd.h", "fmadd.h", "fnmsub.h", "fmv.h.x", "fle.h", "fcvt.l.h", "fcvt.lu.h", "fcvt.h.lu", "fcvt.h.l", "fence.i", "czero.nez", "czero.eqz").contains(i) => UOPCSR.n
      case i if Seq("cdiscard.d.l1", "cflush.d.l1", "hsv.w", "hsv.b", "hfence.vvma", "hlv.hu", "hlvx.hu", "hlv.b", "hlvx.wu", "hlv.w", "hsv.h", "hlv.h", "hlv.bu", "hfence.gvma", "hsv.d", "hlv.d", "hlv.wu", "ebreak", "ecall", "sret", "sfence.vma", "dret", "wfi", "mret", "mnret").contains(i) => UOPCSR.i
      case i if Seq("csrrw", "csrrwi").contains(i) => UOPCSR.w
      case i if Seq("csrrs", "csrrsi").contains(i) => UOPCSR.s
      case i if Seq("csrrc", "csrrci").contains(i) => UOPCSR.c
      case _ => UOPCSR.dontCare
      // format: on
    }

    override def uopType: UOPCSR.type = UOPCSR
  }

  object UOPALU extends UOP {
    def width = 4

    def add: BitPat = encode(0)

    def sl: BitPat = encode(1)

    def seq: BitPat = encode(2)

    def sne: BitPat = encode(3)

    def xor: BitPat = encode(4)

    def sr: BitPat = encode(5)

    def or: BitPat = encode(6)

    def and: BitPat = encode(7)

    def czeqz: BitPat = encode(8)

    def cznez: BitPat = encode(9)

    def sub: BitPat = encode(10)

    def sra: BitPat = encode(11)

    def slt: BitPat = encode(12)

    def sge: BitPat = encode(13)

    def sltu: BitPat = encode(14)

    def sgeu: BitPat = encode(15)

    def div: BitPat = xor

    def divu: BitPat = sr

    def rem: BitPat = or

    def remu: BitPat = and

    def mul: BitPat = add

    def mulh: BitPat = sl

    def mulhsu: BitPat = seq

    def mulhu: BitPat = sne
  }

  object aluFn extends UOPDecodeField[RocketDecodePattern] {
    override def name: String = "alu_fn"

    override def genTable(op: RocketDecodePattern): BitPat = (op.instruction.name, op) match {
      // format: off
      case (i, _) if Seq("amomaxu.w", "amoand.w", "amoor.w", "amoxor.w", "amoswap.w", "lr.w", "amomax.w", "amoadd.w", "amomin.w", "amominu.w", "sc.w", "lr.d", "amomax.d", "amoswap.d", "amoxor.d", "amoand.d", "amomin.d", "amoor.d", "amoadd.d", "amomaxu.d", "amominu.d", "sc.d", "fld", "fsd", "fsw", "flw", "hsv.w", "hsv.b", "hfence.vvma", "hlv.hu", "hlvx.hu", "hlv.b", "hlvx.wu", "hlv.w", "hsv.h", "hlv.h", "hlv.bu", "hfence.gvma", "hsv.d", "hlv.d", "hlv.wu", "or", "srl", "ori", "lhu", "sltu", "sra", "sb", "lw", "add", "xor", "andi", "sltiu", "lh", "jalr", "lui", "lbu", "auipc", "addi", "lb", "jal", "sh", "sll", "srli", "srai", "slli", "ld", "addw", "sd", "sraiw", "lwu", "sllw", "sraw", "subw", "srlw", "addiw", "srliw", "slliw", "mulhsu", "rem", "div", "mul", "mulhu", "mulh", "remu", "divu", "remuw", "divw", "divuw", "mulw", "remw", "sfence.vma", "fsh", "flh", "csrrc", "csrrci", "csrrs", "csrrw", "csrrsi", "csrrwi", "cdiscard.d.l1", "cflush.d.l1").contains(i) => UOPALU.add
      case (i, _) if Seq("and", "andi").contains(i) => UOPALU.and
      case (i, _) if Seq("or", "ori").contains(i) => UOPALU.or
      case (i, _) if Seq("beq").contains(i) => UOPALU.seq
      case (i, _) if Seq("bge").contains(i) => UOPALU.sge
      case (i, _) if Seq("bgeu").contains(i) => UOPALU.sgeu
      case (i, _) if Seq("sll", "slli", "slli", "slliw", "sllw").contains(i) => UOPALU.sl
      case (i, _) if Seq("blt", "slt", "slti").contains(i) => UOPALU.slt
      case (i, _) if Seq("bltu", "sltiu", "sltu").contains(i) => UOPALU.sltu
      case (i, _) if Seq("bne").contains(i) => UOPALU.sne
      case (i, _) if Seq("srl", "srli", "srli", "srliw", "srlw").contains(i) => UOPALU.sr
      case (i, _) if Seq("sra", "srai", "srai", "sraiw", "sraw").contains(i) => UOPALU.sra
      case (i, _) if Seq("sub", "subw").contains(i) => UOPALU.sub
      case (i, _) if Seq("xor", "xori").contains(i) => UOPALU.xor

      // rv_m
      case (i, _) if Seq("mul", "mulw").contains(i) => UOPALU.mul
      case (i, _) if Seq("mulh").contains(i) => UOPALU.mulh
      case (i, _) if Seq("mulhu").contains(i) => UOPALU.mulhu
      case (i, _) if Seq("mulhsu").contains(i) => UOPALU.mulhsu
      case (i, _) if Seq("div", "divw").contains(i) => UOPALU.div
      case (i, _) if Seq("divu", "divuw").contains(i) => UOPALU.divu
      case (i, _) if Seq("rem", "remw").contains(i) => UOPALU.rem
      case (i, _) if Seq("remu", "remuw").contains(i) => UOPALU.remu

      case (i, _) if Seq("czero.eqz").contains(i) => UOPALU.czeqz
      case (i, _) if Seq("czero.nez").contains(i) => UOPALU.cznez
      // vector
      // 7. Vector read RS1 go through ALU rs1 + 0.
      case (_, p) if p.vectorReadRs1 => UOPALU.add
      case _ => UOPALU.dontCare
      // format: on
    }

    override def uopType: UOPALU.type = UOPALU
  }

  object UOPIMM extends UOP {
    def width = 3

    def s: BitPat = encode(0)

    def sb: BitPat = encode(1)

    def u: BitPat = encode(2)

    def uj: BitPat = encode(3)

    def i: BitPat = encode(4)

    def z: BitPat = encode(5)
  }

  object selImm extends UOPDecodeField[RocketDecodePattern] {
    override def name: String = "sel_imm"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      // format: off
      case i if Seq("fld", "flw", "hsv.w", "hsv.b", "hsv.h", "hsv.d", "ori", "lhu", "lw", "andi", "sltiu", "lh", "jalr", "lbu", "xori", "slti", "addi", "lb", "srli", "srai", "slli", "ld", "sraiw", "lwu", "addiw", "srliw", "slliw", "flh").contains(i) => UOPIMM.i
      case i if Seq("fsd", "fsh", "fsw", "sb", "sd", "sh", "sw").contains(i) => UOPIMM.s
      case i if Seq("beq", "bge", "bgeu", "blt", "bltu", "bne").contains(i) => UOPIMM.sb
      case i if Seq("auipc", "lui").contains(i) => UOPIMM.u
      case i if Seq("jal").contains(i) => UOPIMM.uj
      case i if Seq("csrrci", "csrrsi", "csrrwi").contains(i) => UOPIMM.z
      case _ => UOPIMM.dontCare
      // format: on
    }

    override def uopType: UOPIMM.type = UOPIMM
  }

  object UOPA1 extends UOP {
    def width = 2

    def zero: BitPat = encode(0)

    def rs1: BitPat = encode(1)

    def pc: BitPat = encode(2)
  }

  object selAlu1 extends UOPDecodeField[RocketDecodePattern] {
    override def name: String = "sel_alu1"

    override def genTable(op: RocketDecodePattern): BitPat = (op.instruction.name, op) match {
      // format: off
      case (i, _) if Seq("auipc", "jal").contains(i) => UOPA1.pc
      case (i, _) if Seq("amomaxu.w", "amoand.w", "amoor.w", "amoxor.w", "amoswap.w", "lr.w", "amomax.w", "amoadd.w", "amomin.w", "amominu.w", "sc.w", "lr.d", "amomax.d", "amoswap.d", "amoxor.d", "amoand.d", "amomin.d", "amoor.d", "amoadd.d", "amomaxu.d", "amominu.d", "sc.d", "fld", "fcvt.d.wu", "fsd", "fcvt.d.w", "fcvt.d.lu", "fmv.d.x", "fcvt.d.l", "fcvt.s.wu", "fmv.w.x", "fsw", "fcvt.s.w", "flw", "fcvt.s.lu", "fcvt.s.l", "hsv.w", "hsv.b", "hfence.vvma", "hlv.hu", "hlvx.hu", "hlv.b", "hlvx.wu", "hlv.w", "hsv.h", "hlv.h", "hlv.bu", "hfence.gvma", "hsv.d", "hlv.d", "hlv.wu", "or", "srl", "ori", "lhu", "sltu", "sra", "sb", "lw", "add", "xor", "beq", "andi", "bge", "sw", "blt", "bgeu", "sltiu", "lh", "bltu", "jalr", "bne", "lbu", "sub", "and", "xori", "slti", "slt", "addi", "lb", "sh", "sll", "srli", "srai", "slli", "ld", "addw", "sd", "sraiw", "lwu", "sllw", "sraw", "subw", "srlw", "addiw", "srliw", "slliw", "mulhsu", "rem", "div", "mul", "mulhu", "mulh", "remu", "divu", "remuw", "divw", "divuw", "mulw", "remw", "sfence.vma", "fsh", "flh", "fcvt.h.wu", "fcvt.h.w", "fmv.h.x", "fcvt.h.lu", "fcvt.h.l", "csrrc", "csrrs", "csrrw", "czero.nez", "czero.eqz", "cdiscard.d.l1", "cflush.d.l1").contains(i) => UOPA1.rs1
      case (_, p) if p.vectorReadRs1 => UOPA1.rs1
      case (i, _) if Seq("csrrci", "csrrsi", "csrrwi", "lui").contains(i) => UOPA1.zero
      case _ => UOPA1.dontCare
    }

    override def uopType: UOPA1.type = UOPA1
  }

  object UOPA2 extends UOP {
    def width = 2

    def zero: BitPat = encode(0)

    def size: BitPat = encode(1)

    def rs2: BitPat = encode(2)

    def imm: BitPat = encode(3)
  }

  object selAlu2 extends UOPDecodeField[RocketDecodePattern] {
    override def name: String = "sel_alu2"

    override def genTable(op: RocketDecodePattern): BitPat = (op.instruction.name, op) match {
      // format: off
      case (i, _) if Seq("fld", "fsd", "fsw", "flw", "ori", "lhu", "sb", "lw", "andi", "sw", "sltiu", "lh", "jalr", "lui", "lbu", "auipc", "xori", "slti", "addi", "lb", "sh", "srli", "srai", "slli", "ld", "sd", "sraiw", "lwu", "addiw", "srliw", "slliw", "fsh", "flh", "csrrci", "csrrsi", "csrrwi").contains(i) => UOPA2.imm
      case (i, _) if Seq("or", "srl", "sltu", "sra", "add", "xor", "beq", "bge", "blt", "bgeu", "bltu", "bne", "sub", "and", "slt", "sll", "addw", "sllw", "sraw", "subw", "srlw", "mulhsu", "rem", "div", "mul", "mulhu", "mulh", "remu", "divu", "remuw", "divw", "divuw", "mulw", "remw", "czero.nez", "czero.eqz").contains(i) => UOPA2.rs2
      case (i, _) if Seq("jal").contains(i) => UOPA2.size
      case (i, _) if Seq("amomaxu.w", "amoand.w", "amoor.w", "amoxor.w", "amoswap.w", "lr.w", "amomax.w", "amoadd.w", "amomin.w", "amominu.w", "sc.w", "lr.d", "amomax.d", "amoswap.d", "amoxor.d", "amoand.d", "amomin.d", "amoor.d", "amoadd.d", "amomaxu.d", "amominu.d", "sc.d", "hsv.w", "hsv.b", "hfence.vvma", "hlv.hu", "hlvx.hu", "hlv.b", "hlvx.wu", "hlv.w", "hsv.h", "hlv.h", "hlv.bu", "hfence.gvma", "hsv.d", "hlv.d", "hlv.wu", "sfence.vma", "csrrc", "csrrs", "csrrw", "cdiscard.d.l1", "cflush.d.l1").contains(i) => UOPA2.zero
      case (_, p) if p.vectorReadRs1 => UOPA2.zero
      case _ => UOPA2.dontCare
    }

    override def uopType: UOPA2.type = UOPA2
  }

  object vector extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "vector"

    override def genTable(op: RocketDecodePattern): BitPat = if (op.instruction.instructionSet.name == "rv_v") Y else N
  }

  object vectorLSU extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "vectorLSU"

    override def genTable(op: RocketDecodePattern): BitPat = if (op.isVectorLSU) Y else N
  }

  object vectorCSR extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "vectorCSR"

    override def genTable(op: RocketDecodePattern): BitPat = if (op.isVectorCSR) Y else N
  }

  object vectorReadFRs1 extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "vectorReadFRs1"

    override def genTable(op: RocketDecodePattern): BitPat = if (op.vectorReadFRegFile) Y else N
  }

  // fpu decode
  object fldst extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "ldst"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      case i if Seq("flh", "fsh", "flw", "fsw", "fld", "fsd").contains(i) => y
      case i if Seq("fmv.h.x", "fcvt.h.w", "fcvt.h.wu", "fcvt.h.l", "fcvt.h.lu", "fmv.x.h", "fclass.h", "fcvt.w.h", "fcvt.wu.h", "fcvt.l.h", "fcvt.lu.h", "fcvt.s.h", "fcvt.h.s", "feq.h", "flt.h", "fle.h", "fsgnj.h", "fsgnjn.h", "fsgnjx.h", "fmin.h", "fmax.h", "fadd.h", "fsub.h", "fmul.h", "fmadd.h", "fmsub.h", "fnmadd.h", "fnmsub.h", "fdiv.h", "fsqrt.h", "fmv.w.x", "fcvt.s.w", "fcvt.s.wu", "fcvt.s.l", "fcvt.s.lu", "fmv.x.w", "fclass.s", "fcvt.w.s", "fcvt.wu.s", "fcvt.l.s", "fcvt.lu.s", "feq.s", "flt.s", "fle.s", "fsgnj.s", "fsgnjn.s", "fsgnjx.s", "fmin.s", "fmax.s", "fadd.s", "fsub.s", "fmul.s", "fmadd.s", "fmsub.s", "fnmadd.s", "fnmsub.s", "fdiv.s", "fsqrt.s", "fmv.d.x", "fcvt.d.w", "fcvt.d.wu", "fcvt.d.l", "fcvt.d.lu", "fmv.x.d", "fclass.d", "fcvt.w.d", "fcvt.wu.d", "fcvt.l.d", "fcvt.lu.d", "fcvt.s.d", "fcvt.d.s", "feq.d", "flt.d", "fle.d", "fsgnj.d", "fsgnjn.d", "fsgnjx.d", "fmin.d", "fmax.d", "fadd.d", "fsub.d", "fmul.d", "fmadd.d", "fmsub.d", "fnmadd.d", "fnmsub.d", "fdiv.d", "fsqrt.d", "fcvt.h.d", "fcvt.d.h").contains(i) => n
      // todo: dc
      case _ => n
    }
  }

  object fwen extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "wen"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      case i if Seq("flh", "fmv.h.x", "fcvt.h.w", "fcvt.h.wu", "fcvt.h.l", "fcvt.h.lu", "fcvt.s.h", "fcvt.h.s", "fsgnj.h", "fsgnjn.h", "fsgnjx.h", "fmin.h", "fmax.h", "fadd.h", "fsub.h", "fmul.h", "fmadd.h", "fmsub.h", "fnmadd.h", "fnmsub.h", "fdiv.h", "fsqrt.h", "flw", "fmv.w.x", "fcvt.s.w", "fcvt.s.wu", "fcvt.s.l", "fcvt.s.lu", "fsgnj.s", "fsgnjn.s", "fsgnjx.s", "fmin.s", "fmax.s", "fadd.s", "fsub.s", "fmul.s", "fmadd.s", "fmsub.s", "fnmadd.s", "fnmsub.s", "fdiv.s", "fsqrt.s", "fld", "fmv.d.x", "fcvt.d.w", "fcvt.d.wu", "fcvt.d.l", "fcvt.d.lu", "fcvt.s.d", "fcvt.d.s", "fsgnj.d", "fsgnjn.d", "fsgnjx.d", "fmin.d", "fmax.d", "fadd.d", "fsub.d", "fmul.d", "fmadd.d", "fmsub.d", "fnmadd.d", "fnmsub.d", "fdiv.d", "fsqrt.d", "fcvt.h.d", "fcvt.d.h").contains(i) => y
      case _ => n
    }
  }

  object fren1 extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "ren1"

    override def genTable(op: RocketDecodePattern): BitPat = (op.instruction.name, op) match {
      case (i, _) if Seq("fmv.x.h", "fclass.h", "fcvt.w.h", "fcvt.wu.h", "fcvt.l.h", "fcvt.lu.h", "fcvt.s.h", "fcvt.h.s", "feq.h", "flt.h", "fle.h", "fsgnj.h", "fsgnjn.h", "fsgnjx.h", "fmin.h", "fmax.h", "fadd.h", "fsub.h", "fmul.h", "fmadd.h", "fmsub.h", "fnmadd.h", "fnmsub.h", "fdiv.h", "fsqrt.h", "fcvt.d.h").contains(i) => y
      case (_, p) if p.vectorReadFRegFile => y
      case _ => n
    }
  }

  object fren2 extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "ren2"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      case i if Seq("fsh", "feq.h", "flt.h", "fle.h", "fsgnj.h", "fsgnjn.h", "fsgnjx.h", "fmin.h", "fmax.h", "fadd.h", "fsub.h", "fmul.h", "fmadd.h", "fmsub.h", "fnmadd.h", "fnmsub.h", "fdiv.h", "fsw", "feq.s", "flt.s", "fle.s", "fsgnj.s", "fsgnjn.s", "fsgnjx.s", "fmin.s", "fmax.s", "fadd.s", "fsub.s", "fmul.s", "fmadd.s", "fmsub.s", "fnmadd.s", "fnmsub.s", "fdiv.s", "fsd", "feq.d", "flt.d", "fle.d", "fsgnj.d", "fsgnjn.d", "fsgnjx.d", "fmin.d", "fmax.d", "fadd.d", "fsub.d", "fmul.d", "fmadd.d", "fmsub.d", "fnmadd.d", "fnmsub.d", "fdiv.d").contains(i) => y
      case _ => n
    }
  }

  object fren3 extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "ren3"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      case i if Seq("fmadd.h", "fmsub.h", "fnmadd.h", "fnmsub.h", "fmadd.s", "fmsub.s", "fnmadd.s", "fnmsub.s", "fmadd.d", "fmsub.d", "fnmadd.d", "fnmsub.d").contains(i) => y
      case _ => n
    }
  }

  object fswap12 extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "swap12"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      case i if Seq("fsh", "fsw", "fsd").contains(i) => y
      case _ => n
    }
  }

  object fswap23 extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "swap23"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      case i if Seq("fadd.h", "fsub.h", "fadd.s", "fsub.s", "fadd.d", "fsub.d").contains(i) => y
      case _ => n
    }
  }

  object UOPFType extends UOP {
    val helper = new FPUHelper(minFLen, minFLen, xLen)
    // TODO: wtf here.
    def H = BitPat(helper.H)
    def I = BitPat(helper.I)
    def D = BitPat(helper.D)
    def S = BitPat(helper.S)
    def width = D.getWidth
    def X2 = BitPat.dontCare(width)
  }

  object ftypeTagIn extends UOPDecodeField[RocketDecodePattern] {
    override def name: String = "typeTagIn"

    override def genTable(op: RocketDecodePattern): BitPat = (op.instruction.name, op) match {
      case (i, _) if Seq("fsh", "fmv.x.h", "fsw", "fmv.x.w", "fsd", "fmv.x.d").contains(i) => UOPFType.I
      case (i, _) if Seq("fmv.h.x", "fcvt.h.w", "fcvt.h.wu", "fcvt.h.l", "fcvt.h.lu", "fclass.h", "fcvt.w.h", "fcvt.wu.h", "fcvt.l.h", "fcvt.lu.h", "fcvt.s.h", "feq.h", "flt.h", "fle.h", "fsgnj.h", "fsgnjn.h", "fsgnjx.h", "fmin.h", "fmax.h", "fadd.h", "fsub.h", "fmul.h", "fmadd.h", "fmsub.h", "fnmadd.h", "fnmsub.h", "fdiv.h", "fsqrt.h", "fcvt.d.h").contains(i) => UOPFType.H
      case (i, _) if Seq("fcvt.h.s", "fmv.w.x", "fcvt.s.w", "fcvt.s.wu", "fcvt.s.l", "fcvt.s.lu", "fclass.s", "fcvt.w.s", "fcvt.wu.s", "fcvt.l.s", "fcvt.lu.s", "feq.s", "flt.s", "fle.s", "fsgnj.s", "fsgnjn.s", "fsgnjx.s", "fmin.s", "fmax.s", "fadd.s", "fsub.s", "fmul.s", "fmadd.s", "fmsub.s", "fnmadd.s", "fnmsub.s", "fdiv.s", "fsqrt.s", "fcvt.d.s").contains(i) => UOPFType.S
      case (i, _) if Seq("fmv.d.x", "fcvt.d.w", "fcvt.d.wu", "fcvt.d.l", "fcvt.d.lu", "fclass.d", "fcvt.w.d", "fcvt.wu.d", "fcvt.l.d", "fcvt.lu.d", "fcvt.s.d", "feq.d", "flt.d", "fle.d", "fsgnj.d", "fsgnjn.d", "fsgnjx.d", "fmin.d", "fmax.d", "fadd.d", "fsub.d", "fmul.d", "fmadd.d", "fmsub.d", "fnmadd.d", "fnmsub.d", "fdiv.d", "fsqrt.d", "fcvt.h.d").contains(i) => UOPFType.D
      case (_, op) if op.vectorReadFRegFile => UOPFType.I
      case _ => UOPFType.X2
    }

    override def uopType: UOPFType.type = UOPFType
  }

  object ftypeTagOut extends UOPDecodeField[RocketDecodePattern] {
    override def name: String = "typeTagOut"

    override def genTable(op: RocketDecodePattern): BitPat = (op.instruction.name, op) match {
      case (i, _) if Seq("fmv.h.x", "fmv.w.x", "fmv.d.x").contains(i) => UOPFType.I
      case (i, _) if Seq("fsh", "fcvt.h.w", "fcvt.h.wu", "fcvt.h.l", "fcvt.h.lu", "fmv.x.h", "fclass.h", "fcvt.h.s", "feq.h", "flt.h", "fle.h", "fsgnj.h", "fsgnjn.h", "fsgnjx.h", "fmin.h", "fmax.h", "fadd.h", "fsub.h", "fmul.h", "fmadd.h", "fmsub.h", "fnmadd.h", "fnmsub.h", "fdiv.h", "fsqrt.h", "fcvt.h.d").contains(i) => UOPFType.H
      case (i, _) if Seq("fcvt.s.h", "fsw", "fcvt.s.w", "fcvt.s.wu", "fcvt.s.l", "fcvt.s.lu", "fmv.x.w", "fclass.s", "feq.s", "flt.s", "fle.s", "fsgnj.s", "fsgnjn.s", "fsgnjx.s", "fmin.s", "fmax.s", "fadd.s", "fsub.s", "fmul.s", "fmadd.s", "fmsub.s", "fnmadd.s", "fnmsub.s", "fdiv.s", "fsqrt.s", "fcvt.s.d").contains(i) => UOPFType.S
      case (i, _) if Seq("fsd", "fcvt.d.w", "fcvt.d.wu", "fcvt.d.l", "fcvt.d.lu", "fmv.x.d", "fclass.d", "fcvt.d.s", "feq.d", "flt.d", "fle.d", "fsgnj.d", "fsgnjn.d", "fsgnjx.d", "fmin.d", "fmax.d", "fadd.d", "fsub.d", "fmul.d", "fmadd.d", "fmsub.d", "fnmadd.d", "fnmsub.d", "fdiv.d", "fsqrt.d", "fcvt.d.h").contains(i) => UOPFType.D
      case (_, op) if op.vectorReadFRegFile => UOPFType.S
      case _ => UOPFType.X2
    }

    override def uopType: UOPFType.type = UOPFType
  }

  object ffromint extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "fromint"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      case i if Seq("fmv.h.x", "fcvt.h.w", "fcvt.h.wu", "fcvt.h.l", "fcvt.h.lu", "fmv.w.x", "fcvt.s.w", "fcvt.s.wu", "fcvt.s.l", "fcvt.s.lu", "fmv.d.x", "fcvt.d.w", "fcvt.d.wu", "fcvt.d.l", "fcvt.d.lu").contains(i) => y
      case _ => n
    }
  }

  object ftoint extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "toint"

    override def genTable(op: RocketDecodePattern): BitPat = (op.instruction.name, op) match {
      case (i, _) if Seq("fsh", "fmv.x.h", "fclass.h", "fcvt.w.h", "fcvt.wu.h", "fcvt.l.h", "fcvt.lu.h", "feq.h", "flt.h", "fle.h", "fsw", "fmv.x.w", "fclass.s", "fcvt.w.s", "fcvt.wu.s", "fcvt.l.s", "fcvt.lu.s", "feq.s", "flt.s", "fle.s", "fsd", "fmv.x.d", "fclass.d", "fcvt.w.d", "fcvt.wu.d", "fcvt.l.d", "fcvt.lu.d", "feq.d", "flt.d", "fle.d").contains(i) => y
      case (_, op) if op.vectorReadFRegFile => y
      case _ => n
    }
  }

  object ffastpipe extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "fastpipe"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      case i if Seq("fcvt.s.h", "fcvt.h.s", "fsgnj.h", "fsgnjn.h", "fsgnjx.h", "fmin.h", "fmax.h", "fsgnj.s", "fsgnjn.s", "fsgnjx.s", "fmin.s", "fmax.s", "fcvt.s.d", "fcvt.d.s", "fsgnj.d", "fsgnjn.d", "fsgnjx.d", "fmin.d", "fmax.d", "fcvt.h.d", "fcvt.d.h").contains(i) => y
      case _ => n
    }
  }

  object ffma extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "fma"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      case i if Seq("fadd.h", "fsub.h", "fmul.h", "fmadd.h", "fmsub.h", "fnmadd.h", "fnmsub.h", "fadd.s", "fsub.s", "fmul.s", "fmadd.s", "fmsub.s", "fnmadd.s", "fnmsub.s", "fadd.d", "fsub.d", "fmul.d", "fmadd.d", "fmsub.d", "fnmadd.d", "fnmsub.d").contains(i) => y
      case _ => n
    }
  }

  object fdiv extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "div"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      case i if Seq("fdiv.h", "fdiv.s", "fdiv.d").contains(i) => y
      case _ => n
    }
  }

  object fsqrt extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "sqrt"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      case i if Seq("fsqrt.h", "fsqrt.s", "fsqrt.d").contains(i) => y
      case _ => n
    }
  }

  object fwflags extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "wflags"

    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      case i if Seq("fcvt.h.w", "fcvt.h.wu", "fcvt.h.l", "fcvt.h.lu", "fcvt.w.h", "fcvt.wu.h", "fcvt.l.h", "fcvt.lu.h", "fcvt.s.h", "fcvt.h.s", "feq.h", "flt.h", "fle.h", "fmin.h", "fmax.h", "fadd.h", "fsub.h", "fmul.h", "fmadd.h", "fmsub.h", "fnmadd.h", "fnmsub.h", "fdiv.h", "fsqrt.h", "fcvt.s.w", "fcvt.s.wu", "fcvt.s.l", "fcvt.s.lu", "fcvt.w.s", "fcvt.wu.s", "fcvt.l.s", "fcvt.lu.s", "feq.s", "flt.s", "fle.s", "fmin.s", "fmax.s", "fadd.s", "fsub.s", "fmul.s", "fmadd.s", "fmsub.s", "fnmadd.s", "fnmsub.s", "fdiv.s", "fsqrt.s", "fcvt.d.w", "fcvt.d.wu", "fcvt.d.l", "fcvt.d.lu", "fcvt.w.d", "fcvt.wu.d", "fcvt.l.d", "fcvt.lu.d", "fcvt.s.d", "fcvt.d.s", "feq.d", "flt.d", "fle.d", "fmin.d", "fmax.d", "fadd.d", "fsub.d", "fmul.d", "fmadd.d", "fmsub.d", "fnmadd.d", "fnmsub.d", "fdiv.d", "fsqrt.d", "fcvt.h.d", "fcvt.d.h").contains(i) => y
      case _ => n
    }
  }

  object rnd_mode extends UOPDecodeField[RocketDecodePattern] {
    override def name: String = "rnd_mode"
    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      case "fadd.s" | "fsub.s" | "fmul.s" | "fdiv.s" | "fsqrt.s" | "fmadd.s" | "fnmadd.s" | "fmsub.s" | "fnmsub.s" =>
        BitPat("b000") // 默认RNE，实际可根据指令或op内容调整
      case _ => BitPat("b000") // 其它浮点指令默认RNE
    }
    override def uopType = new UOP { def width = 3 }
  }

  object op extends UOPDecodeField[RocketDecodePattern] {
    override def name: String = "op"
    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      case "fadd.s" => BitPat("b00000")
      case "fsub.s" => BitPat("b00001")
      case "fmul.s" => BitPat("b00010")
      case "fdiv.s" => BitPat("b00011")
      // ... 其它浮点操作码 ...
      case _ => BitPat("b00000")
    }
    override def uopType = new UOP { def width = 5 }
  }
  
  object src_fmt extends UOPDecodeField[RocketDecodePattern] {
    override def name: String = "src_fmt"
    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      case i if i.endsWith(".s") => BitPat("b00")
      case i if i.endsWith(".d") => BitPat("b01")
      case i if i.endsWith(".h") => BitPat("b10")
      case _ => BitPat("b00")
    }
    override def uopType = new UOP { def width = 2 }
  }
  
  object dst_fmt extends UOPDecodeField[RocketDecodePattern] {
    override def name: String = "dst_fmt"
    override def genTable(op: RocketDecodePattern): BitPat = op.instruction.name match {
      case i if i.endsWith(".s") => BitPat("b00")
      case i if i.endsWith(".d") => BitPat("b01")
      case i if i.endsWith(".h") => BitPat("b10")
      case _ => BitPat("b00")
    }
    override def uopType = new UOP { def width = 2 }
  }
  
  object int_fmt extends UOPDecodeField[RocketDecodePattern] {
    override def name: String = "int_fmt"
    override def genTable(op: RocketDecodePattern): BitPat = BitPat("b00") // 默认
    override def uopType = new UOP { def width = 2 }
  }
  
  object op_mod extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "op_mod"
    override def genTable(op: RocketDecodePattern): BitPat = BitPat("b0") // 默认false，可根据需要调整
  }
  
  object vectorial_op extends BoolDecodeField[RocketDecodePattern] {
    override def name: String = "vectorial_op"
    override def genTable(op: RocketDecodePattern): BitPat = BitPat("b0") // 默认false
  }
  
  object tag_i extends UOPDecodeField[RocketDecodePattern] {
    override def name: String = "tag_i"
    override def genTable(op: RocketDecodePattern): BitPat = BitPat("b00000") // 默认
    override def uopType = new UOP { def width = 5 }
  }
}

class CoreDecoderInterface(parameter: OGPUDecoderParameter) extends Bundle {
  val instruction = Input(UInt(32.W))
  val output = Output(parameter.coreTable.bundle)
}

class FPUDecoderInterface(parameter: OGPUDecoderParameter) extends Bundle {
  val instruction = Input(UInt(32.W))
  val output = Output(parameter.floatTable.bundle)
}

@instantiable
class CoreDecoder(val parameter: OGPUDecoderParameter)
  extends FixedIORawModule(new CoreDecoderInterface(parameter))
    with SerializableModule[OGPUDecoderParameter]
    with Public {
  io.output := parameter.coreTable.decode(io.instruction)
}

@instantiable
class FPUDecoder(val parameter: OGPUDecoderParameter)
  extends FixedIORawModule(new FPUDecoderInterface(parameter))
    with SerializableModule[OGPUDecoderParameter]
    with Public {
  io.output := parameter.floatTable.decode(io.instruction)
}
