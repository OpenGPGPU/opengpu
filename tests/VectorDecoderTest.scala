import chisel3._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.VCDHackedEphemeralSimulator._
import chisel3.util.experimental.decode.DecodeBundle
import org.chipsalliance.t1.rtl.decoder.{Decoder, DecoderParam}

import ogpu.vector._
import ogpu.core._

class VectorDecoderWrapper(param: DecoderParam) extends Module {
  val io = IO(new Bundle {
    // val clock = Input(Clock())
    val decodeInput = Input(UInt(32.W))
    val decodeResult = Output(new DecodeBundle(Decoder.allFields(param)))
  })

  // withClock(io.clock) {
  val decoder = Module(new VectorDecoder(param))
  decoder.instruction := io.decodeInput
  io.decodeResult := decoder.output
  // }
}

class VectorDecoderTest extends AnyFlatSpec {
  val param = DecoderParam(
    true,
    true,
    OGPUParameter(Set("rv32i", "rvv"), false, false, 16, 32).allInstructions
  )

  behavior.of("VectorDecoder")

  it should "decode basic vector instructions correctly" in {
    simulate(new VectorDecoderWrapper(param), "vectordecodertest1") { dut =>
      // val vaddInstruction = "b1001011_00000_00000_000_00000_1010111".U // vadd.vv编码示例
      val vaddInstruction = "h_02008157".U // vadd.vv编码示例
      dut.io.decodeInput.poke(vaddInstruction)
      dut.clock.step(5)

      dut.io.decodeResult(Decoder.adder).expect(true.B)
    }
  }
}
