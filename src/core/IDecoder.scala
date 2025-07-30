// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2024 Jiuyang Liu <liu@jiuyang.me>
package ogpu.core

import chisel3._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.SerializableModule
import org.chipsalliance.rvdecoderdb.{Encoding, Instruction, InstructionSet}

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

class CoreDecoderInterface(parameter: OGPUParameter) extends Bundle {
  val instruction = Input(UInt(32.W))
  val output = Output(parameter.coreTable.bundle)
}

class FPUDecoderInterface(parameter: OGPUParameter) extends Bundle {
  val instruction = Input(UInt(32.W))
  val output = Output(parameter.floatTable.bundle)
}

@instantiable
class CoreDecoder(val parameter: OGPUParameter)
    extends FixedIORawModule(new CoreDecoderInterface(parameter))
    with SerializableModule[OGPUParameter]
    with Public {
  io.output := parameter.coreTable.decode(io.instruction)
}

@instantiable
class FPUDecoder(val parameter: OGPUParameter)
    extends FixedIORawModule(new FPUDecoderInterface(parameter))
    with SerializableModule[OGPUParameter]
    with Public {
  io.output := parameter.floatTable.decode(io.instruction)
}
