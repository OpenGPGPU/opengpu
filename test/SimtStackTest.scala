import chisel3._

import org.scalatest.flatspec.AnyFlatSpec

import chisel3.util.experimental.loadMemoryFromFileInline
import chisel3.simulator.EphemeralSimulator._

import ogpu.core._

class SimtStackTest extends AnyFlatSpec {
  val param = SimtStackParameter(false, false, 32, 40, 2)
  behavior.of("SimtStack")
  it should "push and pop right" in {
    simulate(new SimtStack(param)) { dut =>
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)
      dut.io.full.expect(false.B)
      dut.io.empty.expect(true.B)
      dut.io.push.poke(true.B)
      dut.io.diverge_in.poke(false.B)
      dut.io.stack_in.pc.poke(0x1024.U)
      dut.io.stack_in.orig_mask(0).poke(true.B)
      dut.io.stack_in.orig_mask(1).poke(true.B)
      dut.io.clock.step()
      dut.io.push.poke(true.B)
      dut.io.diverge_in.poke(true.B)
      dut.io.stack_in.pc.poke(0x1024.U)
      dut.io.stack_in.orig_mask(0).poke(true)
      dut.io.stack_in.orig_mask(1).poke(true)
      dut.io.clock.step()
      dut.io.push.poke(false.B)
      dut.io.full.expect(true.B)
      dut.io.pop.poke(true.B)
      dut.io.clock.step()
      dut.io.full.expect(true.B)
      dut.io.diverge_out.expect(true.B)
      dut.io.stack_out.pc.expect(0x1024.U)
      dut.io.clock.step()
      dut.io.full.expect(false.B)
      dut.io.diverge_out.expect(false.B)
      dut.io.clock.step()
      dut.io.empty.expect(true.B)

    }
  }

}
