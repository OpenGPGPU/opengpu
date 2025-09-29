package ogpu.system

/** System Configuration Presets
  *
  * 预定义的系统配置，用于不同的使用场景
  */
object SystemConfigs {

  /** 最小配置 - 用于测试和开发
    *   - 1个队列，1个作业，1个工作组，1个计算单元
    *   - 适合单元测试和基本功能验证
    */
  val Minimal = OGPUSystemParameter(
    instructionSets = Set("rv_i", "rv_f"),
    pipelinedMul = false,
    fenceIFlushDCache = false,
    warpNum = 2,
    xLen = 32,
    vLen = 64,
    vaddrBitsExtended = 40,
    useAsyncReset = false,
    numQueues = 1,
    numJobs = 1,
    numWorkGroups = 1,
    numComputeUnits = 1,
    warpSize = 32,
    bufferNum = 4
  )

  /** 小型配置 - 用于原型验证
    *   - 2个队列，1个作业，2个工作组，2个计算单元
    *   - 适合原型验证和性能测试
    */
  val Small = OGPUSystemParameter(
    instructionSets = Set("rv_i", "rv_m", "rv_f"),
    pipelinedMul = true,
    fenceIFlushDCache = false,
    warpNum = 4,
    xLen = 64,
    vLen = 128,
    vaddrBitsExtended = 40,
    useAsyncReset = false,
    numQueues = 2,
    numJobs = 1,
    numWorkGroups = 2,
    numComputeUnits = 2,
    warpSize = 32,
    bufferNum = 8
  )

  /** 中型配置 - 用于实际应用
    *   - 4个队列，2个作业，4个工作组，4个计算单元
    *   - 适合中等规模的应用
    */
  val Medium = OGPUSystemParameter(
    instructionSets = Set("rv_i", "rv_m", "rv_a", "rv_f", "rv_d"),
    pipelinedMul = true,
    fenceIFlushDCache = true,
    warpNum = 8,
    xLen = 64,
    vLen = 256,
    vaddrBitsExtended = 48,
    useAsyncReset = false,
    numQueues = 4,
    numJobs = 2,
    numWorkGroups = 4,
    numComputeUnits = 4,
    warpSize = 32,
    bufferNum = 16
  )

  /** 大型配置 - 用于高性能应用
    *   - 8个队列，4个作业，8个工作组，8个计算单元
    *   - 适合高性能计算和图形处理
    */
  val Large = OGPUSystemParameter(
    instructionSets = Set("rv_i", "rv_m", "rv_a", "rv_f", "rv_d", "rv_v"),
    pipelinedMul = true,
    fenceIFlushDCache = true,
    warpNum = 16,
    xLen = 64,
    vLen = 512,
    vaddrBitsExtended = 48,
    useAsyncReset = false,
    numQueues = 8,
    numJobs = 4,
    numWorkGroups = 8,
    numComputeUnits = 8,
    warpSize = 32,
    bufferNum = 32
  )

  /** 最大配置 - 用于最大性能
    *   - 16个队列，8个作业，16个工作组，16个计算单元
    *   - 适合最大性能要求的应用
    */
  val Maximum = OGPUSystemParameter(
    instructionSets = Set("rv_i", "rv_m", "rv_a", "rv_f", "rv_d", "rv_v"),
    pipelinedMul = true,
    fenceIFlushDCache = true,
    warpNum = 32,
    xLen = 64,
    vLen = 1024,
    vaddrBitsExtended = 48,
    useAsyncReset = false,
    numQueues = 16,
    numJobs = 8,
    numWorkGroups = 16,
    numComputeUnits = 16,
    warpSize = 32,
    bufferNum = 64
  )

  /** 向量处理配置 - 专门用于向量计算
    *   - 优化向量指令集支持
    *   - 更大的向量长度和更多的warp
    */
  val VectorOptimized = OGPUSystemParameter(
    instructionSets = Set("rv_i", "rv_m", "rv_f", "rv_d", "rv_v"),
    pipelinedMul = true,
    fenceIFlushDCache = true,
    warpNum = 32,
    xLen = 64,
    vLen = 2048,
    vaddrBitsExtended = 48,
    useAsyncReset = false,
    numQueues = 4,
    numJobs = 2,
    numWorkGroups = 8,
    numComputeUnits = 4,
    warpSize = 32,
    bufferNum = 16
  )

  /** 低功耗配置 - 用于移动和嵌入式应用
    *   - 减少资源使用，降低功耗
    *   - 简化的指令集和较小的缓存
    */
  val LowPower = OGPUSystemParameter(
    instructionSets = Set("rv_i", "rv_f"),
    pipelinedMul = false,
    fenceIFlushDCache = false,
    warpNum = 2,
    xLen = 32,
    vLen = 64,
    vaddrBitsExtended = 32,
    useAsyncReset = true,
    numQueues = 1,
    numJobs = 1,
    numWorkGroups = 1,
    numComputeUnits = 1,
    warpSize = 16,
    bufferNum = 2
  )

  /** 测试配置 - 用于单元测试
    *   - 最小化配置，快速编译和仿真
    *   - 适合CI/CD和自动化测试
    */
  val Test = OGPUSystemParameter(
    instructionSets = Set("rv_i"),
    pipelinedMul = false,
    fenceIFlushDCache = false,
    warpNum = 1,
    xLen = 32,
    vLen = 32,
    vaddrBitsExtended = 32,
    useAsyncReset = false,
    numQueues = 1,
    numJobs = 1,
    numWorkGroups = 1,
    numComputeUnits = 1,
    warpSize = 8,
    bufferNum = 1
  )

  /** 获取配置的字符串表示 */
  def configToString(config: OGPUSystemParameter): String = {
    s"""OGPU System Configuration:
       |  Instruction Sets: ${config.instructionSets.mkString(", ")}
       |  Pipelined Mul: ${config.pipelinedMul}
       |  Fence I Flush DCache: ${config.fenceIFlushDCache}
       |  Warp Number: ${config.warpNum}
       |  X Length: ${config.xLen}
       |  V Length: ${config.vLen}
       |  VAddr Bits Extended: ${config.vaddrBitsExtended}
       |  Use Async Reset: ${config.useAsyncReset}
       |  Number of Queues: ${config.numQueues}
       |  Number of Jobs: ${config.numJobs}
       |  Number of Work Groups: ${config.numWorkGroups}
       |  Number of Compute Units: ${config.numComputeUnits}
       |  Warp Size: ${config.warpSize}
       |  Buffer Number: ${config.bufferNum}
       |""".stripMargin
  }

  /** 验证配置的有效性 */
  def validateConfig(config: OGPUSystemParameter): Boolean = {
    val valid =
      config.warpNum > 0 &&
        config.xLen > 0 &&
        config.vLen > 0 &&
        config.vaddrBitsExtended > 0 &&
        config.numQueues > 0 &&
        config.numJobs > 0 &&
        config.numWorkGroups > 0 &&
        config.numComputeUnits > 0 &&
        config.warpSize > 0 &&
        config.bufferNum > 0

    if (!valid) {
      println("Invalid configuration detected!")
      println(configToString(config))
    }

    valid
  }

  /** 获取所有预定义配置 */
  def getAllConfigs: Map[String, OGPUSystemParameter] = Map(
    "Minimal" -> Minimal,
    "Small" -> Small,
    "Medium" -> Medium,
    "Large" -> Large,
    "Maximum" -> Maximum,
    "VectorOptimized" -> VectorOptimized,
    "LowPower" -> LowPower,
    "Test" -> Test
  )

  /** 根据名称获取配置 */
  def getConfig(name: String): Option[OGPUSystemParameter] = {
    getAllConfigs.get(name)
  }
}
