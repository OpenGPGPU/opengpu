# ComputeUnit 设计文档

## 概述

ComputeUnit 是 OGPU 项目的核心计算单元，它将所有核心模块连接起来组成一个功能完整的 GPU CU (Compute Unit)。该模块实现了完整的指令执行流水线，支持多warp调度、指令缓存、解码、发射、执行和写回等所有必要的功能。

## 架构设计

### 主要组件

ComputeUnit 包含以下主要组件：

1. **Warp Scheduler (WarpScheduler)**
   - 负责warp的调度和管理
   - 处理warp的启动、暂停和恢复
   - 管理warp间的上下文切换

2. **Warp Frontend (WarpFrontend)**
   - 管理每个warp的指令获取
   - 处理分支预测和分支解析
   - 与Frontend模块交互获取指令

3. **Frontend (Frontend)**
   - 指令缓存 (ICache)
   - 指令TLB (ITLB)
   - 指令获取和预取

4. **Decode Pipeline (DecodePipe)**
   - RVC指令扩展
   - 核心指令解码
   - FPU指令解码
   - 向量指令解码

5. **Issue Stage (IssueStage)**
   - 指令发射逻辑
   - 寄存器文件访问
   - Scoreboard管理
   - 数据相关性检查

6. **Execution Units**
   - **ALU Execution (ALUExecution)**: 整数运算单元
   - **FPU Execution (FPUExecution)**: 浮点运算单元

7. **Register Files**
   - **Integer Register File**: 整数寄存器文件
   - **FP Register File**: 浮点寄存器文件
   - **Vector Register File**: 向量寄存器文件

8. **Scoreboards**
   - **Integer Scoreboard**: 整数寄存器相关性跟踪
   - **FP Scoreboard**: 浮点寄存器相关性跟踪
   - **Vector Scoreboard**: 向量寄存器相关性跟踪

9. **Writeback Stage (WritebackStage)**
   - 结果写回寄存器文件
   - Scoreboard清理
   - 异常处理

10. **Scalar Branch (ScalarBranch)**
    - 分支指令解析
    - 分支目标计算
    - 分支预测更新

### 数据流

```
Task Input → WarpScheduler → WarpFrontend → Frontend → DecodePipe → IssueStage → ExecutionUnits → WritebackStage
                ↓
            Register Files & Scoreboards
```

## 接口定义

### 主要接口

```scala
class ComputeUnitInterface(parameter: OGPUParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  
  // 内存接口
  val memory = new Bundle {
    val tilelink = new Bundle {
      val a = DecoupledIO(TLBundleA(...))
      val d = Flipped(DecoupledIO(TLBundleD(...)))
    }
  }
  
  // 任务接口
  val task = Flipped(DecoupledIO(CuTaskBundle(...)))
  
  // 状态和控制信号
  val busy = Output(Bool())
  val idle = Output(Bool())
  val exception = Output(Bool())
}
```

### 任务接口 (CuTaskBundle)

```scala
class CuTaskBundle(
  threadNum: Int, // warp中的线程数
  warpNum: Int,   // warp数量
  dimNum: Int,    // 向量操作维度数
  xLen: Int,      // 标量寄存器宽度
  dimWidth: Int = 16 // 向量维度宽度
) extends Bundle {
  val mask = Vec(threadNum, Bool())        // 线程活跃掩码
  val pc = UInt(32.W)                      // 程序计数器
  val vgprs = Vec(dimNum, UInt(dimWidth.W)) // 向量通用寄存器
  val vgpr_num = UInt(2.W)                 // 活跃VGPR数量
  val sgprs = Vec(16, UInt(xLen.W))       // 标量通用寄存器
  val sgpr_num = UInt(4.W)                 // 活跃SGPR数量
}
```

## 流水线设计

### 流水线阶段

1. **Fetch Stage**
   - WarpFrontend 选择活跃的warp
   - Frontend 从指令缓存获取指令
   - 处理分支预测和分支解析

2. **Decode Stage**
   - RVC指令扩展为32位指令
   - 指令解码为微操作
   - 确定指令类型 (ALU/FPU/Vector)

3. **Issue Stage**
   - 检查数据相关性
   - 分配执行单元
   - 读取操作数

4. **Execute Stage**
   - ALU执行整数运算
   - FPU执行浮点运算
   - 生成分支信息

5. **Writeback Stage**
   - 写回结果到寄存器文件
   - 清理scoreboard
   - 处理异常

### 相关性处理

ComputeUnit 使用scoreboard机制来处理数据相关性：

- **Integer Scoreboard**: 跟踪整数寄存器的使用状态
- **FP Scoreboard**: 跟踪浮点寄存器的使用状态
- **Vector Scoreboard**: 跟踪向量寄存器的使用状态

当指令需要读取寄存器时，会检查scoreboard确保寄存器已准备好。

## 配置参数

### OGPUParameter

```scala
case class OGPUParameter(
  instructionSets: Set[String],    // 支持的指令集
  pipelinedMul: Boolean,           // 流水线乘法器
  fenceIFlushDCache: Boolean,      // fence指令刷新数据缓存
  warpNum: Int = 8,               // warp数量
  minFLen: Int = 16,              // 最小浮点长度
  vLen: Int = 1024,               // 向量长度
  xLen: Int = 32,                 // 标量寄存器宽度
  useAsyncReset: Boolean = false,  // 使用异步复位
  clockGate: Boolean = false,      // 时钟门控
  vaddrBits: Int = 32,            // 虚拟地址位数
  vaddrBitsExtended: Int = 32,    // 扩展虚拟地址位数
  entries: Int = 32,              // 条目数
  coreInstBits: Int = 32,         // 核心指令位数
  fetchWidth: Int = 1,            // 获取宽度
  fetchBufferSize: Int = 8        // 获取缓冲区大小
)
```

## 使用示例

### 基本使用

```scala
// 创建参数
val parameter = OGPUParameter(
  instructionSets = Set("rv_i", "rv_m", "rv_f"),
  pipelinedMul = true,
  fenceIFlushDCache = false,
  warpNum = 4,
  xLen = 32,
  vLen = 512
)

// 实例化ComputeUnit
val computeUnit = Module(new ComputeUnit(parameter))

// 连接接口
computeUnit.io.clock := clock
computeUnit.io.reset := reset
computeUnit.io.memory <> memoryInterface
computeUnit.io.task <> taskInterface
```

### 任务提交

```scala
// 提交任务
computeUnit.io.task.valid := true.B
computeUnit.io.task.bits.mask := "b11111111".U // 8个线程都活跃
computeUnit.io.task.bits.pc := 0x1000.U
computeUnit.io.task.bits.vgprs := 0.U
computeUnit.io.task.bits.sgprs := 0.U

// 检查状态
val isBusy = computeUnit.io.busy
val hasException = computeUnit.io.exception
```

## 测试

### 运行测试

```bash
# 运行所有ComputeUnit测试
sbt "testOnly ogpu.core.ComputeUnitTest"

# 运行特定测试
sbt "testOnly ogpu.core.ComputeUnitTest -- -z \"should initialize correctly\""
```

### 测试覆盖

测试包括：

1. **基本初始化测试**: 验证模块正确初始化
2. **任务提交测试**: 验证任务提交和接受
3. **内存请求测试**: 验证内存接口功能
4. **异常处理测试**: 验证异常检测和处理
5. **多warp测试**: 验证多warp调度

## 性能优化

### 关键优化点

1. **Warp调度**: 使用优先级调度算法
2. **指令缓存**: 预取和缓存管理
3. **寄存器文件**: 多端口访问优化
4. **Scoreboard**: 高效的相关性检查
5. **分支预测**: 减少分支延迟

### 性能监控

ComputeUnit 包含性能计数器：

- 活跃warp数量
- 指令发射率
- 执行单元利用率
- 内存访问统计

## 扩展性

### 支持的功能扩展

1. **向量处理**: 支持RISC-V向量扩展
2. **浮点运算**: 支持单精度和双精度浮点
3. **原子操作**: 支持内存原子操作
4. **虚拟化**: 支持地址转换和TLB
5. **调试支持**: 支持断点和单步执行

### 可配置参数

- warp数量
- 寄存器文件大小
- 缓存配置
- 执行单元数量
- 指令集支持

## 故障排除

### 常见问题

1. **编译错误**: 检查参数配置和接口连接
2. **仿真错误**: 验证时钟和复位信号
3. **功能错误**: 检查数据流和相关性处理
4. **性能问题**: 分析瓶颈和优化点

### 调试技巧

1. 使用debug信号监控流水线状态
2. 检查scoreboard状态
3. 验证寄存器文件访问
4. 监控内存访问模式

## 未来改进

### 计划的功能

1. **动态warp调度**: 更智能的warp调度算法
2. **高级分支预测**: 更准确的分支预测
3. **向量处理优化**: 更高效的向量操作
4. **内存层次优化**: 更好的缓存管理
5. **功耗优化**: 动态电压频率调节

### 架构改进

1. **模块化设计**: 更好的模块分离
2. **接口标准化**: 统一的接口定义
3. **配置灵活性**: 更灵活的配置选项
4. **测试覆盖**: 更全面的测试用例 