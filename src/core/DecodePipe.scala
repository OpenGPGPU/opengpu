package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.Instantiate
import chisel3.experimental.SerializableModule
import chisel3.util.experimental.decode.DecodeBundle
import org.chipsalliance.t1.rtl.decoder.{Decoder, DecoderParam}
import org.chipsalliance.rocketv.RVCExpander
import ogpu.vector._

class InstructionBundle(
  warpNum:           Int,
  instructionLen:    Int,
  vaddrBitsExtended: Int = 48)
    extends Bundle {
  val instruction = UInt(instructionLen.W)
  val wid = UInt(log2Ceil(warpNum).W)
  val pc = UInt(vaddrBitsExtended.W)
}

class DecodePipeInterface(parameter: OGPUParameter) extends Bundle {

  val clock = Input(Clock())
  // val reset = Input(if (parameter.useAsyncReset) AsyncReset() else Bool())
  val reset = Input(Bool())
  val instruction = Flipped(DecoupledIO(new InstructionBundle(parameter.warpNum, 32)))
  val coreResult = DecoupledIO(
    new CoreDecoderInterface(parameter)
  )
  val fpuResult = DecoupledIO(new FPUDecoderInterface(parameter))
  val vectorResult = DecoupledIO(new DecodeBundle(Decoder.allFields(parameter.vector_decode_param)))
  val instruction_out = Output(new InstructionBundle(parameter.warpNum, 32))
  val rvc = Output(Bool())
}

class DecodePipe(val parameter: OGPUParameter)
    extends FixedIORawModule(new DecodePipeInterface(parameter))
    with SerializableModule[OGPUParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // Stage 1: RVC expansion stage signals
  val stage1Valid = RegInit(false.B)
  val stage1Ready = Wire(Bool())

  // Stage 2: Decode stage signals
  val stage2Valid = RegInit(false.B)
  val stage2Ready = Wire(Bool())

  // First stage - RVC expansion
  val rvcDecoder = Instantiate(new RVCExpander(parameter.rvc_decode_param))
  val expanded_instruction = Wire(UInt(32.W))
  val expanded_bundle = Reg(new InstructionBundle(parameter.warpNum, 32))
  val rvc_status = RegInit(false.B) // Add register for RVC status

  // Connect RVC decoder
  rvcDecoder.io.in := io.instruction.bits.instruction
  expanded_instruction := rvcDecoder.io.out.bits

  // Stage 1 pipeline register update
  when(io.instruction.fire) {
    stage1Valid := true.B
    expanded_bundle.instruction := expanded_instruction
    expanded_bundle.wid := io.instruction.bits.wid
    expanded_bundle.pc := io.instruction.bits.pc
    rvc_status := rvcDecoder.io.rvc // Capture RVC status
  }.elsewhen(stage1Valid && stage1Ready) {
    stage1Valid := false.B
  }

  // Second stage - Core/FPU/Vector decoders
  val coreDecoder = Module(new CoreDecoder(parameter))
  val fpuDecoder = Option.when(parameter.useFPU)(Instantiate(new FPUDecoder(parameter)))
  val vectorDecoder = Option.when(parameter.useVector)(
    Instantiate(new VectorDecoder(DecoderParam(true, true, parameter.instructions)))
  )

  // Connect decoders to expanded instruction
  coreDecoder.io.instruction := expanded_bundle.instruction

  // Pipeline registers for decode results
  val coreDecode = RegEnable(coreDecoder.io.output, stage1Valid && stage1Ready)
  val fpuDecode = RegInit(0.U.asTypeOf(io.fpuResult.bits))
  val vectorDecode = RegInit(0.U.asTypeOf(io.vectorResult.bits))
  val instruction_next = Reg(new InstructionBundle(parameter.warpNum, 32))
  val rvc_next = RegInit(false.B) // Add register for next stage RVC status

  // Update stage2Valid when data moves through pipeline
  when(stage1Valid && stage1Ready) {
    stage2Valid := true.B
    instruction_next := expanded_bundle
    rvc_next := rvc_status // Pass RVC status to next stage
  }.elsewhen(stage2Valid && stage2Ready) {
    stage2Valid := false.B
  }

  fpuDecoder.map { fpu =>
    fpu.io.instruction := expanded_bundle.instruction
    when(stage1Valid && stage1Ready) {
      fpuDecode.output := fpu.io.output
      fpuDecode.instruction := expanded_bundle.instruction
    }
  }
  vectorDecoder.map { vector =>
    vector.instruction := expanded_bundle.instruction // Changed from decodeInput
    when(stage1Valid && stage1Ready) {
      vectorDecode := vector.output // Changed from decodeResult
    }
  }

  // Determine instruction type from core decoder
  val isDecodeFp = Option.when(parameter.useFPU)(coreDecode(parameter.fp)).getOrElse(false.B)
  val isDecodeVector = Option.when(parameter.useVector)(coreDecode(parameter.vector)).getOrElse(false.B)
  val isVectorBranch = Option.when(parameter.useVector)(coreDecode(parameter.isVectorBranch)).getOrElse(false.B)

  // Ready signals
  stage2Ready := (io.fpuResult.ready && isDecodeFp) ||
    (io.vectorResult.ready && isDecodeVector) ||
    (io.coreResult.ready && !isDecodeFp && !isDecodeVector)

  stage1Ready := !stage2Valid || stage2Ready
  io.instruction.ready := !stage1Valid || stage1Ready

  // Connect outputs
  io.coreResult.valid := stage2Valid && !isDecodeFp && !isDecodeVector && !isVectorBranch
  io.coreResult.bits.output := coreDecode
  io.coreResult.bits.instruction := instruction_next.instruction

  io.fpuResult.valid := stage2Valid && isDecodeFp
  io.fpuResult.bits := fpuDecode

  io.vectorResult.valid := stage2Valid && (isDecodeVector | isVectorBranch)
  io.vectorResult.bits := vectorDecode

  io.instruction_out := instruction_next
  io.rvc := rvc_next // Connect final RVC status to output
}
