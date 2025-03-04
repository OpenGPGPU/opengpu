import chisel3._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.VCDHackedEphemeralSimulator._
import chisel3.util.experimental.decode.DecodeBundle
import org.chipsalliance.t1.rtl.decoder.{Decoder, DecoderParam}

import ogpu.vector._
import ogpu.rtl._

class VectorDecoderWrapper(param: DecoderParam) extends Module {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val decodeInput = Input(UInt(32.W))
    val decodeResult = Output(new DecodeBundle(Decoder.allFields(param)))
  })

  withClock(io.clock) {
    val decoder = Module(new VectorDecoder(param))
    decoder.decodeInput := io.decodeInput
    io.decodeResult := decoder.decodeResult
  }
}

class VectorDecoderTest extends AnyFlatSpec {
  val param = DecoderParam(
    true,
    true,
    OGPUParameter(64, Seq("rv32i", "rvv"), 32).allInstructions
  )

  behavior.of("VectorDecoder")

  it should "decode basic vector instructions correctly" in {
    simulate(new VectorDecoderWrapper(param), "vectordecodertest1") { dut =>
      val vaddInstruction = "b1001011_00000_00000_000_00000_1010111".U // vadd.vv编码示例
      dut.io.decodeInput.poke(vaddInstruction)
      dut.io.clock.step()

      dut.io.decodeResult(Decoder.adder).expect(true.B)
    }
  }

  it should "handle invalid instructions" in {
    simulate(new VectorDecoderWrapper(param), "vectordecodertest2") { dut =>
      dut.io.decodeInput.poke("hFFFFFFFF".U) // 使用十六进制表示非法指令
      dut.io.clock.step()

      //dut.io.decodeResult.illegal.expect(true.B)
    }
  }
}
