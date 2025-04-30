package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.{instantiable, Instance, Instantiate}
import chisel3.experimental.SerializableModule
import chisel3.util.experimental.decode.DecodeBundle
import org.chipsalliance.t1.rtl.decoder.{Decoder, DecoderParam}
import ogpu.vector._

class ExecutionInterface(parameter: OGPUDecoderParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())

  // Inputs from decode stage
  val coreResult = Flipped(DecoupledIO(new CoreDecoderInterface(parameter)))
  val fpuResult = Flipped(DecoupledIO(new FPUDecoderInterface(parameter)))
  val vectorResult = Flipped(DecoupledIO(new DecodeBundle(Decoder.allFields(parameter.vector_decode_param))))
  val instruction_in = Input(new InstructionBundle(parameter.warpNum, 32))

  // Execution results output
  val execResult = DecoupledIO(new Bundle {
    val result = UInt(parameter.xLen.W)
    val wid = UInt(log2Ceil(parameter.warpNum).W)
    val valid = Bool()
    val exception = Bool()
  })
}

@instantiable
class Execution(val parameter: OGPUDecoderParameter)
    extends FixedIORawModule(new ExecutionInterface(parameter))
    with SerializableModule[OGPUDecoderParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

}
