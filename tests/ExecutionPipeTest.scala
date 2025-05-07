import chisel3._
import chisel3.simulator.VCDHackedEphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import ogpu.core._

class ExecutionPipeTest extends AnyFlatSpec {
  val parameter = OGPUDecoderParameter(
    Set("rv_i", "rv_f"),
    false,
    false,
    16,
    32
  )

  behavior.of("ExecutionPipe")

  it should "handle basic execution pipeline operations correctly" in {
    simulate(new Execution(parameter), "executionpipetest1") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Test basic ALU operation (ADD)
      val testWarpID = 0.U
      val testPC = 0x1000.U
      val testInstruction = "h_3e800093".U //  addi x1 , x0,   1000
      val expectedResult = 1000.U

      // Setup instruction
      dut.io.instruction_in.wid.poke(testWarpID)
      dut.io.instruction_in.pc.poke(testPC)
      dut.io.instruction_in.instruction.poke(testInstruction)

      // Setup register values
      dut.io.coreResult.valid.poke(true.B)

      // Wait for execution
      dut.io.clock.step(3) // Wait for pipeline stages

      // Check result
      dut.io.execResult.valid.expect(true.B)
      dut.io.execResult.bits.result.expect(expectedResult)
      dut.io.execResult.bits.wid.expect(testWarpID)

    }
  }

  it should "handle branch and jump instructions correctly" in {
    simulate(new Execution(parameter), "executionpipetest2") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Test BEQ instruction
      val testWarpID = 0.U
      val testPC = 0x1000.U
      val testInstruction = "h_00208263".U // BEQ rs1, rs2, offset

      // Setup instruction
      dut.io.instruction_in.wid.poke(testWarpID)
      dut.io.instruction_in.pc.poke(testPC)
      dut.io.instruction_in.instruction.poke(testInstruction)

      // Setup register values
      dut.io.coreResult.valid.poke(true.B)

      // Wait for execution
      dut.io.clock.step(3)

      // Check branch taken
      dut.io.execResult.valid.expect(true.B)
    }
  }
}
