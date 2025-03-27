package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.{instantiable, Instance, Instantiate}
import chisel3.experimental.SerializableModule
import chisel3.util.experimental.decode.DecodeBundle
import org.chipsalliance.t1.rtl.decoder.{Decoder, DecoderParam}
import ogpu.vector._

class DecodePipeInterface(parameter: OGPUDecoderParameter) extends Bundle {
  val decode_param = DecoderParam(
    true,
    true,
    OGPUDecoderParameter(Set("rvv"), true, true).instructions
  )
  val instruction = Flipped(DecoupledIO(UInt(32.W))) // 输入指令带握手信号
  val coreResult = DecoupledIO(parameter.coreTable.bundle)
  val fpuResult = DecoupledIO(parameter.floatTable.bundle)
  val vectorResult = DecoupledIO(new DecodeBundle(Decoder.allFields(decode_param)))
}

class DecodePipe(parameter: OGPUDecoderParameter) extends Module {
  val io = IO(new DecodePipeInterface(parameter))

  // Pipeline stage valid and ready signals
  val stage2Ready = Wire(Bool())
  val stage2Valid = RegInit(false.B)

  // First stage - Core decoder
  val coreDecoder = Module(new CoreDecoder(parameter))
  coreDecoder.io.instruction := io.instruction.bits

  // Pipeline registers for core decoder results
  val coreResult = RegEnable(coreDecoder.io.output, io.instruction.fire)

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

  val fpuDecode = WireInit(0.U.asTypeOf(io.fpuResult.bits))
  val vectorDecode = WireInit(0.U.asTypeOf(io.vectorResult.bits))

  fpuDecoder.map { fpu =>
    fpu.io.instruction := io.instruction.bits
    fpuDecode := fpu.io.output
  }
  vectorDecoder.map { vector =>
    vector.decodeInput := io.instruction.bits
    vectorDecode := vector.decodeResult
  }

  // Update stage2Valid when data moves through pipeline
  when(io.instruction.fire) {
    stage2Valid := true.B
  }.otherwise {
    stage2Valid := false.B
  }

  // First stage ready when second stage can accept or is empty
  io.instruction.ready := !stage2Valid || stage2Ready

  val isDecodeFp = Option.when(parameter.useFPU)(coreResult(parameter.fp)).getOrElse(false.B)
  val isDecodeVector = Option.when(parameter.useVector)(coreResult(parameter.vector)).getOrElse(false.B)

  // Stage 2 ready when downstream is ready to accept
  stage2Ready := (io.fpuResult.ready && isDecodeFp) ||
    (io.vectorResult.ready && isDecodeVector) ||
    (io.coreResult.ready && !isDecodeFp && !isDecodeVector)

  // Connect core result output
  io.coreResult.valid := stage2Valid && !isDecodeFp && !isDecodeVector
  io.coreResult.bits := coreResult

  // FPU result output
  io.fpuResult.valid := stage2Valid && isDecodeFp
  io.fpuResult.bits := fpuDecode

  // Vector result output
  io.vectorResult.valid := stage2Valid && isDecodeVector
  io.vectorResult.bits := vectorDecode
}
