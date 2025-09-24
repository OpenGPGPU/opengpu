# OGPU System Integration

这个目录包含了完整的OGPU系统集成，包括core和dispatcher的连接。

## 文件结构

```
src/system/
├── OGPU.scala                    # 主入口模块
├── OGPUSystem.scala             # 核心系统模块
├── OGPUSystemTop.scala          # 顶层系统模块
├── SystemConfigs.scala          # 系统配置预设
└── README.md                    # 本文档
```

## 主要组件

### 1. OGPU.scala
- **功能**: 系统主入口模块
- **特点**: 
  - 提供最顶层的接口
  - 支持多种预定义配置
  - 提供工厂方法创建不同配置的实例

### 2. OGPUSystem.scala
- **功能**: 核心系统模块
- **特点**:
  - 集成所有dispatcher和compute unit
  - 实现完整的任务调度流程
  - 包含内存仲裁器和路由逻辑

### 3. OGPUSystemTop.scala
- **功能**: 顶层系统模块
- **特点**:
  - 时钟域管理
  - 复位管理
  - 性能计数器
  - 中断处理
  - 调试支持

### 4. SystemConfigs.scala
- **功能**: 系统配置预设
- **特点**:
  - 提供多种预定义配置
  - 配置验证功能
  - 配置字符串化

## 系统架构

```
Host System
    ↓
Queue Interfaces (numQueues)
    ↓
Queue-Job Interconnector
    ↓
Job Dispatcher
    ↓
Job-WorkGroup Interconnector
    ↓
WorkGroup Dispatchers (numWorkGroups)
    ↓
WorkGroup-CU Interconnector
    ↓
Compute Units (numComputeUnits)
    ↓
Memory Arbiter
    ↓
System Memory
```

## 配置选项

### 预定义配置

1. **Minimal** - 最小配置，用于测试
2. **Small** - 小型配置，用于原型验证
3. **Medium** - 中型配置，用于实际应用
4. **Large** - 大型配置，用于高性能应用
5. **Maximum** - 最大配置，用于最大性能
6. **VectorOptimized** - 向量处理优化配置
7. **LowPower** - 低功耗配置
8. **Test** - 测试配置

### 自定义配置

```scala
val customConfig = OGPUSystemParameter(
  instructionSets = Set("rv_i", "rv_m", "rv_f"),
  pipelinedMul = true,
  fenceIFlushDCache = false,
  warpNum = 8,
  xLen = 64,
  vLen = 256,
  vaddrBitsExtended = 40,
  useAsyncReset = false,
  numQueues = 4,
  numJobs = 2,
  numWorkGroups = 4,
  numComputeUnits = 4,
  warpSize = 32,
  bufferNum = 16
)

val ogpu = Module(new OGPU(customConfig))
```

## 使用方法

### 基本使用

```scala
// 创建小型配置的OGPU
val ogpu = OGPU.small()

// 或者使用工厂方法
val ogpu = OGPU.create("Small").get
```

### 高级使用

```scala
// 创建自定义配置
val config = OGPUSystemParameter(
  // ... 配置参数
)

// 验证配置
if (OGPU.validateConfig(config)) {
  val ogpu = OGPU.create(config)
  // 使用OGPU
} else {
  println("Invalid configuration!")
}
```

## 接口说明

### 输入接口

- **queues**: 队列接口，接收来自主机的任务
- **memory**: 内存接口，连接到系统内存
- **clock/reset**: 时钟和复位信号

### 输出接口

- **queue_resps**: 队列响应接口
- **debug**: 调试和监控接口
- **interrupts**: 中断接口

### 调试接口

- **systemBusy**: 系统忙碌状态
- **activeComputeUnits**: 活跃的计算单元
- **activeWorkGroups**: 活跃的工作组
- **queueUtilization**: 队列利用率
- **systemStatus**: 系统状态
- **performanceCounters**: 性能计数器

## 测试

系统级测试位于 `tests/OGPUSystemTest.scala`，包含：

1. **系统初始化测试**
2. **队列任务提交测试**
3. **多队列提交测试**
4. **并发计算单元执行测试**
5. **系统复位测试**

## 性能特性

### 并发处理
- 支持多个计算单元并发执行
- 支持多个工作组并发调度
- 支持多个队列并发处理

### 内存管理
- 内存仲裁器支持多计算单元共享内存
- 支持TileLink协议
- 支持页表遍历(PTW)

### 调试支持
- 实时性能监控
- 系统状态监控
- 中断支持

## 扩展性

### 添加新的计算单元
1. 修改 `numComputeUnits` 参数
2. 系统会自动创建相应数量的计算单元
3. 互连器会自动处理连接

### 添加新的队列
1. 修改 `numQueues` 参数
2. 系统会自动创建相应数量的队列接口
3. 调度器会自动处理任务分发

### 自定义调度策略
1. 修改相应的dispatcher模块
2. 实现自定义的调度算法
3. 保持接口兼容性

## 注意事项

1. **配置验证**: 使用前请验证配置的有效性
2. **资源限制**: 确保配置不超过硬件资源限制
3. **时钟域**: 注意时钟域的正确连接
4. **复位序列**: 确保正确的复位序列
5. **内存一致性**: 注意内存访问的一致性

## 故障排除

### 常见问题

1. **编译错误**: 检查配置参数是否有效
2. **仿真失败**: 检查时钟和复位信号
3. **性能问题**: 检查配置是否合理
4. **内存错误**: 检查内存接口连接

### 调试方法

1. 使用调试接口监控系统状态
2. 检查性能计数器
3. 使用VCD波形分析
4. 检查日志输出
