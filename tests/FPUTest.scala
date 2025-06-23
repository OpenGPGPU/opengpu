package ogpu.fpu

import chisel3._
import chisel3.simulator.VCDHackedEphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import ogpu.core.OGPUDecoderParameter
import java.nio.ByteBuffer
import java.nio.ByteOrder

object FloatToUInt {
  def apply(f: Float): UInt = {
    val buffer = ByteBuffer.allocate(4)
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    buffer.putFloat(f)
    val intValue = buffer.getInt(0)
    intValue.U(32.W)
  }
}

class FPUTest extends AnyFlatSpec {

  val param = OGPUDecoderParameter(
    Set("rv_i", "rv_f"),
    false,
    false,
    16,
    32
  )

  behavior.of("FPU")

  it should "handle basic FPU operations correctly (FADD.S)" in {
    simulate(new FPU(param), "fpu_fadd_s") { dut =>
      // Initialize
      dut.io.clk_i.step()
      dut.io.rst_ni.poke(false.B)
      dut.io.clk_i.step()
      dut.io.rst_ni.poke(true.B)

      // Test FADD.S operation
      val opA = FloatToUInt(10.0f)
      val opB = FloatToUInt(1.0f)
      val opC = FloatToUInt(5.0f)
      val expectedResult = FloatToUInt(15.0f)

      // Setup input signals
      dut.io.clk_i.step()
      dut.io.op_a.poke(opA)
      dut.io.op_b.poke(opB)
      dut.io.op_c.poke(opC)
      dut.io.rnd_mode.poke("b000".U)
      dut.io.op.poke("b00000".U)
      dut.io.op_mod.poke(false.B)
      dut.io.src_fmt.poke("b00".U)
      dut.io.dst_fmt.poke("b00".U)
      dut.io.int_fmt.poke("b00".U)
      dut.io.vectorial_op.poke(false.B)
      dut.io.tag_i.poke(0.U)
      dut.io.in_valid.poke(true.B)
      dut.io.out_ready.poke(true.B)

      dut.io.clk_i.step(5)

      // Check result
      dut.io.result.expect(expectedResult)
      dut.io.status.expect(0.U)
      dut.io.tag_o.expect(0.U)
      dut.io.out_valid.expect(true.B)
    }
  }

  it should "handle FPU subtraction operations correctly (FSUB.S)" in {
    simulate(new FPU(param), "fpu_fsub_s") { dut =>
      // Initialize
      dut.io.clk_i.step()
      dut.io.rst_ni.poke(false.B)
      dut.io.clk_i.step()
      dut.io.rst_ni.poke(true.B)

      // Test FSUB.S operation
      val opA = FloatToUInt(20.0f)
      val opB = FloatToUInt(1.0f)
      val opC = FloatToUInt(10.0f)
      val expectedResult = FloatToUInt(10.0f)

      // Setup input signals
      dut.io.clk_i.step()
      dut.io.op_a.poke(opA)
      dut.io.op_b.poke(opB)
      dut.io.op_c.poke(opC)
      dut.io.rnd_mode.poke("b000".U)
      dut.io.op.poke("b00000".U)
      dut.io.op_mod.poke(true.B)
      dut.io.src_fmt.poke("b00".U)
      dut.io.dst_fmt.poke("b00".U)
      dut.io.int_fmt.poke("b00".U)
      dut.io.vectorial_op.poke(false.B)
      dut.io.tag_i.poke(0.U)
      dut.io.in_valid.poke(true.B)
      dut.io.out_ready.poke(true.B)

      dut.io.clk_i.step(5)

      // Check result
      dut.io.result.expect(expectedResult)
      dut.io.status.expect(0.U)
      dut.io.tag_o.expect(0.U)
      dut.io.out_valid.expect(true.B)
    }
  }
}
