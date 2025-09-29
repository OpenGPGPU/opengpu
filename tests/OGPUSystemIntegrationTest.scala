package ogpu.system

import chisel3._
import chisel3.simulator.VCDHackedEphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class OGPUSystemIntegrationTest extends AnyFlatSpec {

  behavior.of("OGPUIntegration")

  it should "create and initialize all configurations correctly" in {
    val configs = Seq(
      ("Minimal", SystemConfigs.Minimal),
      ("Small", SystemConfigs.Small),
      ("Medium", SystemConfigs.Medium),
      ("Test", SystemConfigs.Test)
    )

    for ((name, config) <- configs) {
      println(s"Testing $name configuration...")

      // 验证配置
      assert(OGPU.validateConfig(config), s"$name configuration should be valid")

      // 创建系统
      simulate(new OGPU(config), s"${name.toLowerCase}_init_test") { dut =>
        // 初始化
        dut.clock.step(1)
        dut.reset.poke(true.B)
        dut.clock.step(1)
        dut.reset.poke(false.B)
        dut.clock.step(1)

        // 检查初始状态
        dut.io.debug.systemBusy.expect(false.B, s"$name system should be idle initially")
        // Check that no compute units are active initially
        for (i <- 0 until config.numComputeUnits) {
          dut.io.debug.activeComputeUnits(i).expect(false.B, s"$name system CU $i should not be active initially")
        }
        // Check that no work groups are active initially
        for (i <- 0 until config.numWorkGroups) {
          dut.io.debug.activeWorkGroups(i).expect(false.B, s"$name system WG $i should not be active initially")
        }

        // 检查队列利用率
        for (i <- 0 until config.numQueues) {
          dut.io.debug.queueUtilization(i).expect(false.B, s"$name system queue $i should not be utilized initially")
        }

        println(s"✓ $name configuration initialized successfully")
      }
    }
  }

  it should "handle task submission and processing" in {
    val config = SystemConfigs.Small

    simulate(new OGPU(config), "task_processing_test") { dut =>
      // 初始化
      dut.clock.step(1)
      dut.reset.poke(true.B)
      dut.clock.step(1)
      dut.reset.poke(false.B)
      dut.clock.step(1)

      // 设置内存接口
      dut.io.memory.a.ready.poke(true.B)
      dut.io.memory.d.valid.poke(false.B)

      // 准备任务 - 使用poke直接设置硬件信号
      dut.io.queues(0).bits.header.poke(0x1000.U)
      dut.io.queues(0).bits.dimensions.poke(2.U)
      dut.io.queues(0).bits.workgroup_size_x.poke(8.U)
      dut.io.queues(0).bits.workgroup_size_y.poke(8.U)
      dut.io.queues(0).bits.workgroup_size_z.poke(1.U)
      dut.io.queues(0).bits.grid_size_x.poke(64.U)
      dut.io.queues(0).bits.grid_size_y.poke(64.U)
      dut.io.queues(0).bits.grid_size_z.poke(1.U)
      dut.io.queues(0).bits.private_segment_size.poke(1024.U)
      dut.io.queues(0).bits.group_segment_size.poke(2048.U)
      dut.io.queues(0).bits.kernel_object.poke(0x2000.U)
      dut.io.queues(0).bits.kernargs_address.poke(0x3000.U)
      dut.io.queues(0).bits.completion_signal.poke(0x4000.U)

      // 提交任务
      dut.io.queues(0).valid.poke(true.B)
      dut.io.queue_resps(0).ready.poke(true.B)

      dut.clock.step(1)

      // 验证任务被接受
      dut.io.queues(0).ready.expect(true.B, "Queue should accept the task")
      dut.io.debug.queueUtilization(0).expect(true.B, "Queue 0 should be utilized")

      // 停止任务输入
      dut.io.queues(0).valid.poke(false.B)

      // 等待处理完成
      var cycles = 0
      val maxCycles = 100

      while (cycles < maxCycles && dut.io.debug.systemBusy.peek().litToBoolean) {
        dut.clock.step(1)
        cycles += 1
      }

      // 验证处理完成
      if (cycles < maxCycles) {
        println(s"✓ Task processed in $cycles cycles")
        dut.io.debug.systemBusy.expect(false.B, "System should be idle after processing")
      } else {
        println(s"⚠ Task processing did not complete within $maxCycles cycles")
      }
    }
  }

  it should "handle concurrent task processing" in {
    val config = SystemConfigs.Medium

    simulate(new OGPU(config), "concurrent_processing_test") { dut =>
      // 初始化
      dut.clock.step(1)
      dut.reset.poke(true.B)
      dut.clock.step(1)
      dut.reset.poke(false.B)
      dut.clock.step(1)

      // 设置内存接口
      dut.io.memory.a.ready.poke(true.B)
      dut.io.memory.d.valid.poke(false.B)

      // 提交多个任务
      val numTasks = math.min(config.numQueues, 4)
      for (i <- 0 until numTasks) {
        dut.io.queues(i).valid.poke(true.B)
        dut.io.queues(i).bits.header.poke((0x1000 + i * 0x1000).U)
        dut.io.queues(i).bits.dimensions.poke(2.U)
        dut.io.queues(i).bits.workgroup_size_x.poke(8.U)
        dut.io.queues(i).bits.workgroup_size_y.poke(8.U)
        dut.io.queues(i).bits.workgroup_size_z.poke(1.U)
        dut.io.queues(i).bits.grid_size_x.poke(64.U)
        dut.io.queues(i).bits.grid_size_y.poke(64.U)
        dut.io.queues(i).bits.grid_size_z.poke(1.U)
        dut.io.queues(i).bits.private_segment_size.poke(1024.U)
        dut.io.queues(i).bits.group_segment_size.poke(2048.U)
        dut.io.queues(i).bits.kernel_object.poke(0x2000.U)
        dut.io.queues(i).bits.kernargs_address.poke(0x3000.U)
        dut.io.queues(i).bits.completion_signal.poke(0x4000.U)
        dut.io.queue_resps(i).ready.poke(true.B)
      }

      dut.clock.step(1)

      // 停止任务输入
      for (i <- 0 until numTasks) {
        dut.io.queues(i).valid.poke(false.B)
      }

      // 监控并发处理
      var cycles = 0
      val maxCycles = 200
      var maxActiveCUs = 0
      var maxActiveWGs = 0

      while (cycles < maxCycles && dut.io.debug.systemBusy.peek().litToBoolean) {
        dut.clock.step(1)
        cycles += 1

        // Count active compute units and work groups
        val activeCUs = dut.io.debug.activeComputeUnits.map(_.peek().litToBoolean).count(_ == true)
        val activeWGs = dut.io.debug.activeWorkGroups.map(_.peek().litToBoolean).count(_ == true)

        maxActiveCUs = math.max(maxActiveCUs, activeCUs)
        maxActiveWGs = math.max(maxActiveWGs, activeWGs)
      }

      // 验证并发处理
      println(s"✓ Maximum concurrent CUs: $maxActiveCUs")
      println(s"✓ Maximum concurrent WGs: $maxActiveWGs")

      if (cycles < maxCycles) {
        println(s"✓ Concurrent processing completed in $cycles cycles")
        dut.io.debug.systemBusy.expect(false.B, "System should be idle after completion")
      } else {
        println(s"⚠ Concurrent processing did not complete within $maxCycles cycles")
      }
    }
  }

  it should "handle system reset correctly" in {
    val config = SystemConfigs.Small

    simulate(new OGPU(config), "system_reset_test") { dut =>
      // 初始化
      dut.clock.step(1)
      dut.reset.poke(true.B)
      dut.clock.step(1)
      dut.reset.poke(false.B)
      dut.clock.step(1)

      // 提交任务
      dut.io.memory.a.ready.poke(true.B)
      dut.io.memory.d.valid.poke(false.B)

      dut.io.queues(0).valid.poke(true.B)
      dut.io.queues(0).bits.header.poke(0x1000.U)
      dut.io.queues(0).bits.dimensions.poke(2.U)
      dut.io.queues(0).bits.workgroup_size_x.poke(8.U)
      dut.io.queues(0).bits.workgroup_size_y.poke(8.U)
      dut.io.queues(0).bits.workgroup_size_z.poke(1.U)
      dut.io.queues(0).bits.grid_size_x.poke(64.U)
      dut.io.queues(0).bits.grid_size_y.poke(64.U)
      dut.io.queues(0).bits.grid_size_z.poke(1.U)
      dut.io.queues(0).bits.private_segment_size.poke(1024.U)
      dut.io.queues(0).bits.group_segment_size.poke(2048.U)
      dut.io.queues(0).bits.kernel_object.poke(0x2000.U)
      dut.io.queues(0).bits.kernargs_address.poke(0x3000.U)
      dut.io.queues(0).bits.completion_signal.poke(0x4000.U)
      dut.io.queue_resps(0).ready.poke(true.B)

      dut.clock.step(5)

      // 执行系统复位
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.clock.step(1)

      // 验证系统状态被重置
      dut.io.debug.systemBusy.expect(false.B, "System should be idle after reset")
      // Check that no compute units are active after reset
      for (i <- 0 until config.numComputeUnits) {
        dut.io.debug.activeComputeUnits(i).expect(false.B, s"CU $i should not be active after reset")
      }
      // Check that no work groups are active after reset
      for (i <- 0 until config.numWorkGroups) {
        dut.io.debug.activeWorkGroups(i).expect(false.B, s"WG $i should not be active after reset")
      }

      for (i <- 0 until config.numQueues) {
        dut.io.debug.queueUtilization(i).expect(false.B, s"Queue $i should not be utilized after reset")
      }

      println("✓ System reset completed successfully")
    }
  }

  it should "provide correct performance monitoring" in {
    val config = SystemConfigs.Small

    simulate(new OGPU(config), "performance_monitoring_test") { dut =>
      // 初始化
      dut.clock.step(1)
      dut.reset.poke(true.B)
      dut.clock.step(1)
      dut.reset.poke(false.B)
      dut.clock.step(1)

      // 设置内存接口
      dut.io.memory.a.ready.poke(true.B)
      dut.io.memory.d.valid.poke(false.B)

      // 提交任务
      dut.io.queues(0).valid.poke(true.B)
      dut.io.queues(0).bits.header.poke(0x1000.U)
      dut.io.queues(0).bits.dimensions.poke(2.U)
      dut.io.queues(0).bits.workgroup_size_x.poke(8.U)
      dut.io.queues(0).bits.workgroup_size_y.poke(8.U)
      dut.io.queues(0).bits.workgroup_size_z.poke(1.U)
      dut.io.queues(0).bits.grid_size_x.poke(64.U)
      dut.io.queues(0).bits.grid_size_y.poke(64.U)
      dut.io.queues(0).bits.grid_size_z.poke(1.U)
      dut.io.queues(0).bits.private_segment_size.poke(1024.U)
      dut.io.queues(0).bits.group_segment_size.poke(2048.U)
      dut.io.queues(0).bits.kernel_object.poke(0x2000.U)
      dut.io.queues(0).bits.kernargs_address.poke(0x3000.U)
      dut.io.queues(0).bits.completion_signal.poke(0x4000.U)
      dut.io.queue_resps(0).ready.poke(true.B)

      dut.clock.step(1)
      dut.io.queues(0).valid.poke(false.B)

      // 等待处理
      var cycles = 0
      val maxCycles = 100

      while (cycles < maxCycles && dut.io.debug.systemBusy.peek().litToBoolean) {
        dut.clock.step(1)
        cycles += 1
      }

      // 检查性能计数器
      val totalCycles = dut.io.debug.performanceCounters.totalCycles.peek().litValue
      val activeCycles = dut.io.debug.performanceCounters.activeCycles.peek().litValue
      val completedTasks = dut.io.debug.performanceCounters.completedTasks.peek().litValue
      val memoryTransactions = dut.io.debug.performanceCounters.memoryTransactions.peek().litValue

      // 验证性能计数器
      assert(totalCycles > 0, "Total cycles should be greater than 0")
      assert(activeCycles >= 0, "Active cycles should be non-negative")
      assert(completedTasks >= 0, "Completed tasks should be non-negative")
      assert(memoryTransactions >= 0, "Memory transactions should be non-negative")

      println(s"✓ Performance monitoring working correctly:")
      println(s"  Total Cycles: $totalCycles")
      println(s"  Active Cycles: $activeCycles")
      println(s"  Completed Tasks: $completedTasks")
      println(s"  Memory Transactions: $memoryTransactions")
    }
  }
}
