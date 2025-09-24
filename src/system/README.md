# OGPU System Integration

This directory contains the complete OGPU system integration, including connections between core and dispatcher components.

## File Structure

```
src/system/
├── OGPU.scala                    # Main entry module
├── OGPUSystem.scala             # Core system module
├── OGPUSystemTop.scala          # Top-level system module
├── SystemConfigs.scala          # System configuration presets
└── README.md                    # This document
```

## Main Components

### 1. OGPU.scala
- **Function**: Main system entry module
- **Features**: 
  - Provides top-level interfaces
  - Supports multiple predefined configurations
  - Provides factory methods to create instances with different configurations

### 2. OGPUSystem.scala
- **Function**: Core system module
- **Features**:
  - Integrates all dispatchers and compute units
  - Implements complete task scheduling flow
  - Includes memory arbiter and routing logic

### 3. OGPUSystemTop.scala
- **Function**: Top-level system module
- **Features**:
  - Clock domain management
  - Reset management
  - Performance counters
  - Interrupt handling
  - Debug support

### 4. SystemConfigs.scala
- **Function**: System configuration presets
- **Features**:
  - Provides multiple predefined configurations
  - Configuration validation functionality
  - Configuration stringification

## System Architecture

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

## Configuration Options

### Predefined Configurations

1. **Minimal** - Minimal configuration for testing
2. **Small** - Small configuration for prototyping
3. **Medium** - Medium configuration for practical applications
4. **Large** - Large configuration for high-performance applications
5. **Maximum** - Maximum configuration for peak performance
6. **VectorOptimized** - Vector processing optimized configuration
7. **LowPower** - Low power configuration
8. **Test** - Test configuration

### Custom Configuration

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

## Usage

### Basic Usage

```scala
// Create a small configuration OGPU
val ogpu = OGPU.small()

// Or use factory methods
val ogpu = OGPU.create("Small").get
```

### Advanced Usage

```scala
// Create custom configuration
val config = OGPUSystemParameter(
  // ... configuration parameters
)

// Validate configuration
if (OGPU.validateConfig(config)) {
  val ogpu = OGPU.create(config)
  // Use OGPU
} else {
  println("Invalid configuration!")
}
```

## Interface Description

### Input Interfaces

- **queues**: Queue interfaces that receive tasks from the host
- **memory**: Memory interface connected to system memory
- **clock/reset**: Clock and reset signals

### Output Interfaces

- **queue_resps**: Queue response interfaces
- **debug**: Debug and monitoring interfaces
- **interrupts**: Interrupt interfaces

### Debug Interfaces

- **systemBusy**: System busy status
- **activeComputeUnits**: Active compute units
- **activeWorkGroups**: Active work groups
- **queueUtilization**: Queue utilization
- **systemStatus**: System status
- **performanceCounters**: Performance counters

## Testing

System-level tests are located in `tests/OGPUSystemTest.scala`, including:

1. **System initialization test**
2. **Queue task submission test**
3. **Multiple queue submission test**
4. **Concurrent compute unit execution test**
5. **System reset test**

## Performance Features

### Concurrent Processing
- Supports concurrent execution of multiple compute units
- Supports concurrent scheduling of multiple work groups
- Supports concurrent processing of multiple queues

### Memory Management
- Memory arbiter supports multiple compute units sharing memory
- Supports TileLink protocol
- Supports Page Table Walk (PTW)

### Debug Support
- Real-time performance monitoring
- System status monitoring
- Interrupt support

## Extensibility

### Adding New Compute Units
1. Modify the `numComputeUnits` parameter
2. The system will automatically create the corresponding number of compute units
3. Interconnectors will automatically handle connections

### Adding New Queues
1. Modify the `numQueues` parameter
2. The system will automatically create the corresponding number of queue interfaces
3. Schedulers will automatically handle task distribution

### Custom Scheduling Strategies
1. Modify the corresponding dispatcher modules
2. Implement custom scheduling algorithms
3. Maintain interface compatibility

## Notes

1. **Configuration Validation**: Please validate configuration validity before use
2. **Resource Limits**: Ensure configuration does not exceed hardware resource limits
3. **Clock Domains**: Pay attention to correct clock domain connections
4. **Reset Sequence**: Ensure correct reset sequence
5. **Memory Consistency**: Pay attention to memory access consistency

## Troubleshooting

### Common Issues

1. **Compilation Errors**: Check if configuration parameters are valid
2. **Simulation Failures**: Check clock and reset signals
3. **Performance Issues**: Check if configuration is reasonable
4. **Memory Errors**: Check memory interface connections

### Debug Methods

1. Use debug interfaces to monitor system status
2. Check performance counters
3. Use VCD waveform analysis
4. Check log output
