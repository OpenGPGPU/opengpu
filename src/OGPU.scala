/** OGPU (Open GPU) RTL Package
  *
  * This package contains the core RTL (Register Transfer Level) implementation of the Open GPU architecture. It
  * includes the main OGPU module and related components for vector processing.
  */
package ogpu.rtl

import chisel3.experimental.SerializableModuleParameter
import org.chipsalliance.rvdecoderdb.Instruction

/** OGPUParameter defines the configuration parameters for the OGPU module
  *
  * @param dLen
  *   Data path width in bits
  * @param extensions
  *   List of supported instruction set extensions
  * @param vrfBankSize
  *   Size of each bank in the Vector Register File
  */
case class OGPUParameter(
  dLen:        Int, // Data path width in bits
  extensions:  Seq[String], // Supported instruction set extensions
  vrfBankSize: Int // Size of each VRF bank
) extends SerializableModuleParameter {
  // TODO: expose it with the Property API

  /** Retrieves and filters all supported vector instructions
    *
    * @return
    *   Sequence of Instruction objects representing supported vector operations Excludes vector configuration
    *   instructions (vset*) and includes only instructions from the RVV (RISC-V Vector) extension
    */
  val allInstructions: Seq[Instruction] = {
    org.chipsalliance.rvdecoderdb
      .instructions(org.chipsalliance.rvdecoderdb.extractResource(getClass.getClassLoader))
      .filter { instruction =>
        instruction.instructionSet.name match {
          case "rv_v" => true // Include only RVV instructions
          case _      => false
        }
      }
  }.toSeq.filter { insn =>
    // println(insn.instructionSet.name)
    insn.name match {
      case s if Seq("vsetivli", "vsetvli", "vsetvl").contains(s) => false // Exclude vector config instructions
      case _                                                     => true
    }
  }.sortBy(_.instructionSet.name) // Sort instructions by their instruction set

}

/** ALURTL (Arithmetic Logic Unit Register Transfer Level) Test Object
  *
  * This object serves as a test harness for the OGPUParameter class, demonstrating its usage and printing the supported
  * instructions
  */
object ALURTL extends App {
  val p = new OGPUParameter(64, Seq("rvv"), 32)
  println(p.allInstructions)
}
