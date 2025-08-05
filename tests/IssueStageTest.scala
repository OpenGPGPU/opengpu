package ogpu.core

import chisel3._
import chisel3.simulator.VCDHackedEphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class IssueStageTest extends AnyFlatSpec {
  val parameter = OGPUParameter(
    Set("rv_v", "rv_f"),
    false,
    false,
    16,
    32
  )

  behavior.of("IssueStage")

  it should "set and clear busy bits via scoreboard when issuing instructions" in {
    simulate(new IssueStage(parameter), "issue_stage_set_clear") { dut =>
      dut.io.clock.step()
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // 模拟输入一条ALU指令，rd=2
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.instruction.poke("h002000b3".U) // add x1, x0, x2 (rd=1, rs1=0, rs2=2)
      dut.io.in.bits.instruction.wid.poke(0.U)
      dut.io.in.bits.coreResult.output(parameter.wxd).poke(true.B)
      dut.io.in.bits.coreResult.output(parameter.execType).poke(0.U)
      dut.io.in.bits.coreResult.output(parameter.selAlu1).poke(1.U)
      dut.io.in.bits.coreResult.output(parameter.selAlu2).poke(2.U)

      dut.io.aluIssue.ready.poke(true.B)
      dut.io.fpuIssue.ready.poke(false.B)

      dut.io.clock.step()
      dut.io.aluIssue.valid.expect(true.B)
    }
  }
}
