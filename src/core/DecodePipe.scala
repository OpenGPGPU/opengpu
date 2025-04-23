package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.{instantiable, Instance, Instantiate}
import chisel3.experimental.SerializableModule
import chisel3.util.experimental.decode.DecodeBundle
import org.chipsalliance.t1.rtl.decoder.{Decoder, DecoderParam}
import ogpu.vector._

class InstructionBundle(
  warpNum:        Int,
  instructionLen: Int)
    extends Bundle {
  val instruction = UInt(instructionLen.W)
  val wid = UInt(log2Ceil(warpNum).W)
}

class DecodePipeInterface(parameter: OGPUDecoderParameter) extends Bundle {

  val clock = Input(Clock())
  // val reset = Input(if (parameter.useAsyncReset) AsyncReset() else Bool())
  val reset = Input(Bool())
  val instruction = Flipped(DecoupledIO(new InstructionBundle(parameter.warpNum, 32)))
  val coreResult = DecoupledIO(
    new CoreDecoderInterface(parameter)
  )
  val fpuResult = DecoupledIO(new FPUDecoderInterface(parameter))
  val vectorResult = DecoupledIO(new DecodeBundle(Decoder.allFields(parameter.decode_param)))
  val instruction_out = Output(new InstructionBundle(parameter.warpNum, 32))
}

class DecodePipe(val parameter: OGPUDecoderParameter)
    extends FixedIORawModule(new DecodePipeInterface(parameter))
    with SerializableModule[OGPUDecoderParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {
  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // Pipeline stage valid and ready signals
  val stage2Ready = Wire(Bool())
  val stage2Valid = RegInit(false.B)

  // First stage - Core decoder
  val coreDecoder = Module(new CoreDecoder(parameter))
  coreDecoder.io.instruction := io.instruction.bits.instruction

  // Pipeline registers for core decoder results
  val coreDecode = RegEnable(coreDecoder.io.output, io.instruction.fire)

  // Second stage - FPU and Vector decoders
  val fpuDecoder: Option[Instance[FPUDecoder]] =
    Option.when(parameter.useFPU)(Instantiate(new FPUDecoder(parameter)))
  val vectorDecoder: Option[Instance[VectorDecoder]] =
    Option.when(parameter.useVector)(
      Instantiate(
        new VectorDecoder(
          DecoderParam(
            true,
            true,
            parameter.instructions
          )
        )
      )
    )

  val fpuDecode = RegInit(0.U.asTypeOf(io.fpuResult.bits))
  val vectorDecode = RegInit(0.U.asTypeOf(io.vectorResult.bits))
  val instruction_next = Reg(new InstructionBundle(parameter.warpNum, 32))

  fpuDecoder.map { fpu =>
    fpu.io.instruction := io.instruction.bits.instruction
    when(io.instruction.fire) {
      fpuDecode.output := fpu.io.output
      fpuDecode.instruction := instruction_next.instruction
    }
  }
  vectorDecoder.map { vector =>
    vector.decodeInput := io.instruction.bits.instruction
    when(io.instruction.fire) {
      vectorDecode := vector.decodeResult
    }
  }

  // Update stage2Valid when data moves through pipeline
  when(io.instruction.fire) {
    stage2Valid := true.B
    instruction_next := io.instruction.bits
  }.elsewhen(stage2Valid && stage2Ready) {
    stage2Valid := false.B
  }

  // First stage ready when second stage can accept or is empty
  io.instruction.ready := !stage2Valid || stage2Ready

  val isDecodeFp = Option.when(parameter.useFPU)(coreDecode(parameter.fp)).getOrElse(false.B)
  val isDecodeVector = Option.when(parameter.useVector)(coreDecode(parameter.vector)).getOrElse(false.B)

  // Stage 2 ready when downstream is ready to accept
  stage2Ready := (io.fpuResult.ready && isDecodeFp) ||
    (io.vectorResult.ready && isDecodeVector) ||
    (io.coreResult.ready && !isDecodeFp && !isDecodeVector)

  // Connect core result output
  io.coreResult.valid := stage2Valid && !isDecodeFp && !isDecodeVector
  io.coreResult.bits.output := coreDecode
  io.coreResult.bits.instruction := instruction_next.instruction

  // FPU result output
  io.fpuResult.valid := stage2Valid && isDecodeFp
  io.fpuResult.bits := fpuDecode

  // Vector result output
  io.vectorResult.valid := stage2Valid && isDecodeVector
  io.vectorResult.bits := vectorDecode

  io.instruction_out := instruction_next
}
