package ogpu.core

import chisel3._
import chisel3.simulator.VCDHackedEphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class ComputeUnitTest extends AnyFlatSpec {

  "ComputeUnit" should "compile successfully" in {
    val parameter = OGPUParameter(
      instructionSets = Set("rv_i", "rv_m", "rv_a", "rv_f", "rv_d", "rv_v"),
      pipelinedMul = true,
      fenceIFlushDCache = false,
      warpNum = 4,
      xLen = 64,
      vLen = 128,
      vaddrBitsExtended = 40
    )

    simulate(new ComputeUnit(parameter), "compile_test") { dut =>
      // 基本初始化
      dut.io.clock.step(1)
      dut.io.reset.poke(true.B)
      dut.io.clock.step(1)
      dut.io.reset.poke(false.B)
      dut.io.clock.step(1)

      // 检查初始状态
      dut.io.idle.expect(true.B, "Should be idle initially")
      dut.io.busy.expect(false.B, "Should not be busy initially")
      dut.io.exception.expect(false.B, "Should not have exception initially")
    }
  }

  "ComputeUnit" should "handle task submission" in {
    val parameter = OGPUParameter(
      instructionSets = Set("rv_i", "rv_m", "rv_a", "rv_f", "rv_d", "rv_v"),
      pipelinedMul = true,
      fenceIFlushDCache = false,
      warpNum = 4,
      xLen = 64,
      vLen = 128,
      vaddrBitsExtended = 40
    )

    simulate(new ComputeUnit(parameter), "task_test") { dut =>
      // 初始化
      dut.io.clock.step(1)
      dut.io.reset.poke(true.B)
      dut.io.clock.step(1)
      dut.io.reset.poke(false.B)
      dut.io.clock.step(1)

      // 提交任务
      dut.io.task.valid.poke(true.B)
      dut.io.task.bits.pc.poke(0x1000.U)
      dut.io.task.bits.mask.foreach(_.poke(true.B))
      dut.io.task.bits.vgpr_num.poke(1.U)
      dut.io.task.bits.sgpr_num.poke(1.U)
      dut.io.clock.step(1)

      // 检查任务被接受
      dut.io.task.ready.expect(true.B, "Task should be ready to accept")
    }
  }

  "ComputeUnit" should "handle memory requests" in {
    val parameter = OGPUParameter(
      instructionSets = Set("rv_i", "rv_m", "rv_a", "rv_f", "rv_d", "rv_v"),
      pipelinedMul = true,
      fenceIFlushDCache = false,
      warpNum = 4,
      xLen = 64,
      vLen = 128,
      vaddrBitsExtended = 40
    )

    simulate(new ComputeUnit(parameter), "memory_test") { dut =>
      // 初始化
      dut.io.clock.step(1)
      dut.io.reset.poke(true.B)
      dut.io.clock.step(1)
      dut.io.reset.poke(false.B)
      dut.io.clock.step(1)

      // 检查内存接口
      dut.io.memory.a.ready.poke(true.B)
      dut.io.memory.d.valid.poke(false.B)
      dut.io.clock.step(1)

      // 验证内存接口状态
      dut.io.memory.a.valid.expect(false.B, "Memory request should not be valid initially")
    }
  }

  "ComputeUnit" should "provide debug information" in {
    val parameter = OGPUParameter(
      instructionSets = Set("rv_i", "rv_m", "rv_a", "rv_f", "rv_d", "rv_v"),
      pipelinedMul = true,
      fenceIFlushDCache = false,
      warpNum = 4,
      xLen = 64,
      vLen = 128,
      vaddrBitsExtended = 40
    )

    simulate(new ComputeUnit(parameter), "debug_test") { dut =>
      // 初始化
      dut.io.clock.step(1)
      dut.io.reset.poke(true.B)
      dut.io.clock.step(1)
      dut.io.reset.poke(false.B)
      dut.io.clock.step(1)

      // 检查调试信号
      dut.io.debug.frontendBusy.expect(false.B, "Frontend should not be busy initially")
      dut.io.debug.executionBusy.expect(false.B, "Execution should not be busy initially")
      dut.io.debug.dataManagerBusy.expect(false.B, "Data manager should not be busy initially")
    }
  }
}
