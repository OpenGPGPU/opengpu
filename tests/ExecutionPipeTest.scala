package ogpu.core

import chisel3._
import chisel3.simulator.VCDHackedEphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class ExecutionPipeTest extends AnyFlatSpec {
  val parameter = OGPUDecoderParameter(
    Set("rv_i", "rv_f"),
    false,
    false,
    16,
    32
  )

  behavior.of("Execution")

  it should "handle basic ALU operations correctly" in {
    simulate(new Execution(parameter), "execution_alu_add") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Test add operation
      val testWarpID = 0.U
      val rs1Data = 1000.U
      val rs2Data = 2000.U
      val expectedResult = 3000.U

      // Setup input signals
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.warpID.poke(testWarpID)
      dut.io.in.bits.funct3.poke("b000".U) // ADD
      dut.io.in.bits.funct7.poke("b0000000".U)
      dut.io.in.bits.rs1Data.poke(rs1Data)
      dut.io.in.bits.rs2Data.poke(rs2Data)
      dut.io.in.bits.rd.poke(1.U)
      dut.io.in.bits.pc.poke(0x1000.U)

      dut.io.clock.step()

      // Check result
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.result.expect(expectedResult)
      dut.io.out.bits.warpID.expect(testWarpID)
      dut.io.out.bits.rd.expect(1.U)
      dut.io.out.bits.exception.expect(false.B)
    }
  }

  it should "handle subtraction operations correctly" in {
    simulate(new Execution(parameter), "execution_alu_sub") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Test sub operation
      val testWarpID = 1.U
      val rs1Data = 2000.U
      val rs2Data = 1000.U
      val expectedResult = 1000.U

      // Setup input signals
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.warpID.poke(testWarpID)
      dut.io.in.bits.funct3.poke("b010".U) // SUB
      dut.io.in.bits.funct7.poke("b0100000".U)
      dut.io.in.bits.rs1Data.poke(rs1Data)
      dut.io.in.bits.rs2Data.poke(rs2Data)
      dut.io.in.bits.rd.poke(2.U)
      dut.io.in.bits.pc.poke(0x1004.U)

      dut.io.clock.step(3)

      // Check result
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.result.expect(expectedResult)
      dut.io.out.bits.warpID.expect(testWarpID)
      dut.io.out.bits.rd.expect(2.U)
      dut.io.out.bits.exception.expect(false.B)
    }
  }

  it should "handle pipeline bubbles correctly" in {
    simulate(new Execution(parameter)) { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Test invalid input (bubble)
      dut.io.in.valid.poke(false.B)

      dut.io.clock.step()

      // Check that output is invalid
      dut.io.out.valid.expect(false.B)
    }
  }
}
