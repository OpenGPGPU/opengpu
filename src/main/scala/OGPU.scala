package ogpu.rtl

import chisel3._
import chisel3.experimental.hierarchy.{instantiable, public, Instance, Instantiate}
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}
import chisel3.probe.{define, Probe, ProbeValue}
import chisel3.properties.{AnyClassType, Class, ClassType, Property}
import chisel3.ltl.{CoverProperty, Sequence}
import chisel3.util.experimental.BitSet
import chisel3.util.experimental.decode.DecodeBundle
import chisel3.util.{
  log2Ceil,
  scanLeftOr,
  scanRightOr,
  BitPat,
  Decoupled,
  DecoupledIO,
  Enum,
  Fill,
  FillInterleaved,
  Mux1H,
  OHToUInt,
  Pipe,
  RegEnable,
  UIntToOH,
  Valid,
  ValidIO
}
import org.chipsalliance.rvdecoderdb.Instruction

import scala.collection.immutable.SeqMap

case class OGPUParameter(
  dLen:       Int,
  extensions: Seq[String],
  // Lane
  vrfBankSize: Int)
    extends SerializableModuleParameter {
  // TODO: expose it with the Property API

  val allInstructions: Seq[Instruction] = {
    org.chipsalliance.rvdecoderdb
      .instructions(org.chipsalliance.rvdecoderdb.extractResource(getClass.getClassLoader))
      .filter { instruction =>
        instruction.instructionSet.name match {
          case "rv_v" => true
          case _      => false
        }
      }
  }.toSeq.filter { insn =>
    insn.name match {
      case s if Seq("vsetivli", "vsetvli", "vsetvl").contains(s) => false
      case _                                                     => true
    }
  }.sortBy(_.instructionSet.name)

}

object ALURTL extends App {
  val p = new OGPUParameter(64, Seq("rvv"), 32)
  println(p.allInstructions)
}
