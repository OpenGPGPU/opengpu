import chisel3._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.VCDHackedEphemeralSimulator._

import ogpu.core._

class DecodePipeTest extends AnyFlatSpec {
  val param = OGPUDecoderParameter(
    Set("rv_v", "rv_f"),
    false,
    false,
    16,
    32
  )

  behavior.of("DecodePipe")

  it should "decode core instructions correctly" in {
    simulate(new DecodePipe(param), "decodepipetest1") { dut =>
      // Test ADD instruction (R-type)
      val addInstruction = "b0000000_00001_00010_000_00011_0110011".U

      // Send instruction
      dut.io.instruction.valid.poke(true.B)
      dut.io.instruction.bits.instruction.poke(addInstruction)
      dut.io.coreResult.ready.poke(true.B)
      dut.io.clock.step()
      dut.io.clock.step()

      // Check if core result is valid
      dut.io.coreResult.valid.expect(true.B)
      dut.io.fpuResult.valid.expect(false.B)
      dut.io.vectorResult.valid.expect(false.B)
    }
  }

  it should "decode floating-point instructions correctly" in {
    simulate(new DecodePipe(param), "decodepipetest2") { dut =>
      // Test FADD.S instruction
      val faddInstruction = "b0000000_00001_00010_000_00011_1010011".U

      // Send instruction
      dut.io.instruction.valid.poke(true.B)
      dut.io.instruction.bits.instruction.poke(faddInstruction)
      dut.io.fpuResult.ready.poke(true.B)
      dut.io.clock.step(2)

      // Second cycle: FPU decoder
      dut.io.coreResult.valid.expect(false.B)
      dut.io.fpuResult.valid.expect(true.B)
      dut.io.vectorResult.valid.expect(false.B)
    }
  }

  it should "decode vector instructions correctly" in {
    simulate(new DecodePipe(param), "decodepipetest3") { dut =>
      // Test VADD.VV instruction
      val vaddInstruction = "h02008157".U

      // Send instruction
      dut.io.instruction.valid.poke(true.B)
      dut.io.instruction.bits.instruction.poke(vaddInstruction)
      dut.io.vectorResult.ready.poke(true.B)
      dut.io.clock.step(2)

      // Second cycle: Vector decoder
      dut.io.coreResult.valid.expect(false.B)
      dut.io.fpuResult.valid.expect(false.B)
      dut.io.vectorResult.valid.expect(true.B)
    }
  }

  it should "handle backpressure correctly" in {
    simulate(new DecodePipe(param), "decodepipetest4") { dut =>
      // Test backpressure by not setting ready signals
      val instruction = "b0000000_00001_00010_000_00011_0110011".U

      // Send instruction but keep downstream not ready
      dut.io.instruction.valid.poke(true.B)
      dut.io.instruction.bits.instruction.poke(instruction)
      dut.io.coreResult.ready.poke(false.B)
      dut.io.fpuResult.ready.poke(false.B)
      dut.io.vectorResult.ready.poke(false.B)

      // Check if pipeline stalls
      dut.io.clock.step(2)
      dut.io.instruction.ready.expect(false.B)

      // Make downstream ready
      dut.io.coreResult.ready.poke(true.B)
      dut.io.clock.step()

      // Pipeline should move again
      dut.io.instruction.ready.expect(true.B)
    }
  }
}
