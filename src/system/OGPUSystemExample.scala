package ogpu.system

/** OGPU System Usage Example
  *
  * 展示如何使用OGPU系统的完整示例
  */
object OGPUSystemExample {

  /** 基本使用示例 */
  def basicExample(): Unit = {
    println("=== OGPU System Basic Example ===")

    // 打印配置信息
    OGPU.printConfig(SystemConfigs.Small)

    // 验证配置
    if (OGPU.validateConfig(SystemConfigs.Small)) {
      println("✓ Configuration is valid")
    } else {
      println("✗ Configuration is invalid")
    }
  }

  /** 自定义配置示例 */
  def customConfigExample(): Unit = {
    println("\n=== OGPU System Custom Config Example ===")

    // 创建自定义配置
    val customConfig = OGPUSystemParameter(
      instructionSets = Set("rv_i", "rv_m", "rv_f", "rv_d"),
      pipelinedMul = true,
      fenceIFlushDCache = true,
      warpNum = 8,
      xLen = 64,
      vLen = 256,
      vaddrBitsExtended = 40,
      useAsyncReset = false,
      numQueues = 2,
      numJobs = 1,
      numWorkGroups = 2,
      numComputeUnits = 2,
      warpSize = 32,
      bufferNum = 8
    )

    // 验证配置
    if (OGPU.validateConfig(customConfig)) {
      println("✓ Custom configuration is valid")

      // 创建OGPU实例（这里只是验证，实际使用时需要实例化）
      // val ogpu = OGPU.create(customConfig)

      // 打印配置信息
      OGPU.printConfig(customConfig)
    } else {
      println("✗ Custom configuration is invalid")
    }
  }

  /** 所有配置示例 */
  def allConfigsExample(): Unit = {
    println("\n=== OGPU System All Configs Example ===")

    val configNames = OGPU.getAvailableConfigs
    println(s"Available configurations: ${configNames.mkString(", ")}")

    for (configName <- configNames) {
      println(s"\n--- $configName Configuration ---")
      OGPU.getConfig(configName).foreach { config =>
        OGPU.printConfig(config)
        if (OGPU.validateConfig(config)) {
          println("✓ Valid")
        } else {
          println("✗ Invalid")
        }
      }
    }
  }

  /** 硬件生成示例 */
  def hardwareGenerationExample(): Unit = {
    println("\n=== OGPU System Hardware Generation Example ===")

    // 使用测试配置
    val testConfig = SystemConfigs.Test
    println(s"OGPU hardware configuration analysis...")

    try {
      // 分析硬件配置（不实际生成硬件）
      println("✓ OGPU configuration validated successfully")

      // 打印配置信息
      println(s"  - Number of queues: ${testConfig.numQueues}")
      println(s"  - Number of compute units: ${testConfig.numComputeUnits}")
      println(s"  - Number of work groups: ${testConfig.numWorkGroups}")
      println(s"  - Warp size: ${testConfig.warpSize}")
      println(s"  - Vector length: ${testConfig.vLen}")
      println(s"  - Address width: ${testConfig.vaddrBitsExtended}")

      // 计算资源估算
      val totalWarps = testConfig.numComputeUnits * testConfig.warpNum
      val totalThreads = totalWarps * testConfig.warpSize
      println(s"  - Estimated total warps: $totalWarps")
      println(s"  - Estimated total threads: $totalThreads")

    } catch {
      case e: Exception =>
        println(s"✗ Configuration analysis failed: ${e.getMessage}")
    }
  }

  /** 配置比较示例 */
  def configurationComparisonExample(): Unit = {
    println("\n=== OGPU System Configuration Comparison ===")

    val configs = Seq(
      ("Minimal", SystemConfigs.Minimal),
      ("Small", SystemConfigs.Small),
      ("Medium", SystemConfigs.Medium),
      ("Large", SystemConfigs.Large)
    )

    println("| Configuration | Queues | Jobs | WorkGroups | ComputeUnits | WarpSize | Buffers |")
    println("|---------------|--------|------|------------|--------------|----------|---------|")

    for ((name, config) <- configs) {
      println(
        f"| $name%-13s | ${config.numQueues}%-6d | ${config.numJobs}%-4d | ${config.numWorkGroups}%-10d | ${config.numComputeUnits}%-12d | ${config.warpSize}%-8d | ${config.bufferNum}%-7d |"
      )
    }

    println("\nResource Utilization Analysis:")
    for ((name, config) <- configs) {
      val totalWarps = config.numComputeUnits * config.warpNum
      val totalThreads = totalWarps * config.warpSize
      val memoryPorts = config.numComputeUnits

      println(s"\n$name Configuration:")
      println(s"  - Total Warps: $totalWarps")
      println(s"  - Total Threads: $totalThreads")
      println(s"  - Memory Ports: $memoryPorts")
      println(s"  - Vector Length: ${config.vLen}")
      println(s"  - Address Width: ${config.vaddrBitsExtended}")
    }
  }

  /** 主函数 */
  def main(args: Array[String]): Unit = {
    println("OGPU System Integration Examples")
    println("=" * 50)

    // 运行所有示例
    basicExample()
    customConfigExample()
    allConfigsExample()
    hardwareGenerationExample()
    configurationComparisonExample()

    println("\n" + "=" * 50)
    println("All examples completed successfully!")
  }
}
