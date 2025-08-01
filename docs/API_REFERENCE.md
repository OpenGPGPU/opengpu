# API Reference

## Core Components

### OGPU - Main GPU Module

The main GPU module that integrates all components.

```scala
class OGPU(val parameter: OGPUParameter) extends Module
```

#### Parameters
- `warpNum`: Number of warps
- `vaddrBits`: Virtual address width
- `paddrBits`: Physical address width
- `coreInstBits`: Core instruction width
- `useFPU`: Enable floating-point unit
- `useVector`: Enable vector processing

#### Interface
```scala
val io = IO(new Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  // Memory interface
  val memory = new TLLink(parameter.memoryParameter)
  // Control interface
  val control = new OGPUControlInterface(parameter)
})
```

### Frontend - Instruction Fetch

Handles instruction fetching and basic decoding.

```scala
class Frontend(val parameter: FrontendParameter) extends Module
```

#### Parameters
- `warpNum`: Number of warps
- `fetchWidth`: Instructions fetched per cycle
- `vaddrBits`: Virtual address width
- `coreInstBits`: Core instruction width

#### Interface
```scala
val io = IO(new Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val instructionFetchTileLink: TLLink
  val nonDiplomatic = new FrontendBundle(...)
})
```

### WarpScheduler - Warp Management

Manages multiple warps and their execution.

```scala
class WarpScheduler(val parameter: OGPUParameter) extends Module
```

#### Parameters
- `warpNum`: Number of warps
- `vaddrBits`: Virtual address width

#### Interface
```scala
val io = IO(new Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val warp_start = Flipped(DecoupledIO(...))
  val warp_finish = Input(Valid(UInt(...)))
  val frontend_req = DecoupledIO(...)
  val frontend_resp = Flipped(DecoupledIO(...))
})
```

## Memory System

### ICache - Instruction Cache

Instruction cache with TileLink interface.

```scala
class ICache(val parameter: ICacheParameter) extends Module
```

#### Parameters
- `nSets`: Number of cache sets
- `nWays`: Number of ways per set
- `blockBytes`: Block size in bytes
- `vaddrBits`: Virtual address width
- `paddrBits`: Physical address width

#### Interface
```scala
val io = IO(new Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val instructionFetchTileLink: TLLink
  val itimTileLink: Option[TLLink]
})
```

### DCache - Data Cache

Data cache for data access with TileLink interface.

```scala
class DCache(val parameter: DCacheParameter) extends Module
```

#### Parameters
- `nSets`: Number of cache sets
- `nWays`: Number of ways per set
- `blockBytes`: Block size in bytes
- `vaddrBits`: Virtual address width
- `paddrBits`: Physical address width
- `dataWidth`: Data width in bits
- `nMSHRs`: Number of MSHR entries

#### Interface
```scala
val io = IO(new Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val req = Flipped(Decoupled(new DCacheReq(parameter)))
  val resp = Decoupled(new DCacheResp(parameter))
      val memory = new DMemoryIO(parameter)
  val ptwMem = Flipped(Decoupled(new PTWMemoryReq(...)))
  val ptwMemResp = Decoupled(new PTWMemoryResp(...))
})
```

### TLB - Translation Lookaside Buffer

Address translation with virtual memory support.

```scala
class TLB(val parameter: TLBParameter) extends Module
```

#### Parameters
- `vaddrBits`: Virtual address width
- `paddrBits`: Physical address width
- `asidBits`: Address space ID width
- `pgLevels`: Page table levels

#### Interface
```scala
val io = IO(new Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val req = Flipped(Decoupled(new TLBReq(...)))
  val resp = Output(new TLBResp(...))
  val ptw = new TLBPTWIO(...)
})
```

### PTW - Page Table Walker

Page table walking for virtual memory.

```scala
class PTW(val parameter: PTWParameter) extends Module
```

#### Parameters
- `xLen`: RISC-V register width
- `vaddrBits`: Virtual address width
- `paddrBits`: Physical address width
- `pgLevels`: Page table levels
- `asidBits`: Address space ID width

#### Interface
```scala
val io = IO(new Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val tlb = new Bundle {
    val req = Flipped(Decoupled(Valid(new PTWReq(...))))
    val resp = Valid(new PTWResp(...))
    val ptbr = Input(new PTBR(...))
  }
  val mem = Decoupled(new PTWMemoryReq(...))
  val memResp = Flipped(Decoupled(new PTWMemoryResp(...)))
})
```

## Execution Units

### ALUExecution - Integer Execution

Integer arithmetic and logic operations.

```scala
class ALUExecution(val parameter: OGPUParameter) extends Module
```

#### Interface
```scala
val io = IO(new Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val in = Flipped(Decoupled(new ALUExecutionReq(parameter)))
  val out = Decoupled(new ALUExecutionResp(parameter))
  val regFile = new RegisterFileInterface(parameter)
})
```

### FPUExecution - Floating-Point Execution

Floating-point arithmetic operations.

```scala
class FPUExecution(val parameter: OGPUParameter) extends Module
```

#### Interface
```scala
val io = IO(new Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val in = Flipped(Decoupled(new FPUExecutionReq(parameter)))
  val out = Decoupled(new FPUExecutionResp(parameter))
  val regFile = new RegisterFileInterface(parameter)
})
```

### VectorALU - Vector Processing

RISC-V Vector extension support.

```scala
class VectorALU(val parameter: OGPUParameter) extends Module
```

#### Interface
```scala
val io = IO(new Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val in = Flipped(Decoupled(new VectorALUReq(parameter)))
  val out = Decoupled(new VectorALUResp(parameter))
  val vectorRegFile = new VectorRegisterFileInterface(parameter)
})
```

## Dispatcher Components

### JobDispatcher - Job Management

Manages job dispatching and workgroup creation.

```scala
class JobDispatcher(val parameter: OGPUParameter) extends Module
```

#### Interface
```scala
val io = IO(new Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val queue = Flipped(Decoupled(new QueueBundle()))
  val task = Decoupled(new TaskBundle(parameter))
})
```

### WorkGroupDispatcher - Workgroup Management

Manages workgroup dispatching to compute units.

```scala
class WorkGroupDispatcher(val parameter: OGPUParameter) extends Module
```

#### Interface
```scala
val io = IO(new Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val task = Flipped(Decoupled(new TaskBundle(parameter)))
  val workgroup = Decoupled(new WorkGroupBundle(parameter))
})
```

## Data Types

### Bundle Definitions

#### PTWReq - Page Table Walk Request
```scala
class PTWReq(val vpnBits: Int) extends Bundle {
  val addr = UInt(vpnBits.W)
  val stage2 = Bool()
}
```

#### PTWResp - Page Table Walk Response
```scala
class PTWResp(val vaddrBits: Int, val pgLevels: Int) extends Bundle {
  val ae_ptw = Bool()
  val ae_final = Bool()
  val pf = Bool()
  val pte = new PTE
  val level = UInt(log2Ceil(pgLevels).W)
  val fragmented_superpage = Bool()
  val homogeneous = Bool()
}
```

#### DCacheReq - Data Cache Request
```scala
class DCacheReq(val parameter: DCacheParameter) extends Bundle {
  val vaddr = UInt(parameter.vaddrBits.W)
  val cmd = UInt(2.W)
  val size = UInt(3.W)
  val data = UInt(parameter.dataWidth.W)
}
```

#### DCacheResp - Data Cache Response
```scala
class DCacheResp(val parameter: DCacheParameter) extends Bundle {
  val vaddr = UInt(parameter.vaddrBits.W)
  val data = UInt(parameter.dataWidth.W)
  val exception = Bool()
}
```

### Parameter Classes

#### OGPUParameter - Main GPU Parameters
```scala
case class OGPUParameter(
  useAsyncReset: Boolean,
  warpNum: Int,
  vaddrBits: Int,
  paddrBits: Int,
  coreInstBits: Int,
  useFPU: Boolean,
  useVector: Boolean,
  // ... additional parameters
)
```

#### ICacheParameter - Instruction Cache Parameters
```scala
case class ICacheParameter(
  useAsyncReset: Boolean,
  nSets: Int,
  nWays: Int,
  blockBytes: Int,
  vaddrBits: Int,
  paddrBits: Int,
  // ... additional parameters
)
```

#### DCacheParameter - Data Cache Parameters
```scala
case class DCacheParameter(
  useAsyncReset: Boolean,
  nSets: Int,
  nWays: Int,
  blockBytes: Int,
  vaddrBits: Int,
  paddrBits: Int,
  dataWidth: Int,
  nMSHRs: Int,
  // ... additional parameters
)
```

## Configuration Examples

### Basic GPU Configuration
```scala
val basicParams = OGPUParameter(
  useAsyncReset = false,
  warpNum = 32,
  vaddrBits = 39,
  paddrBits = 32,
  coreInstBits = 32,
  useFPU = true,
  useVector = true
)
```

### High-Performance Configuration
```scala
val highPerfParams = OGPUParameter(
  useAsyncReset = false,
  warpNum = 64,
  vaddrBits = 48,
  paddrBits = 40,
  coreInstBits = 32,
  useFPU = true,
  useVector = true
)
```

### Memory Configuration
```scala
val cacheParams = DCacheParameter(
  useAsyncReset = false,
  nSets = 128,
  nWays = 8,
  blockBytes = 64,
  vaddrBits = 39,
  paddrBits = 32,
  dataWidth = 64,
  nMSHRs = 8
)
``` 