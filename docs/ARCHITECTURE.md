# OPENGPU Architecture

## Overview

OPENGPU is a simplified GPU implementation built with Chisel, featuring RISC-V Vector (RVV) extension support and TileLink-based memory interfaces for better cache coherence.

## System Architecture

### High-Level Architecture

The system follows a hierarchical design with the following layers:

1. **Software Layer** - Applications and drivers
2. **Hardware Interface Layer** - Queue management and scheduling
3. **Dispatch Layer** - Job and workgroup dispatching
4. **Compute Unit Layer** - Core execution pipeline
5. **Memory Layer** - Cache hierarchy and external memory

### Core Components

#### 1. Frontend Pipeline
- **Warp Scheduler**: Manages multiple warps and their execution
- **Fetch Queue**: Buffers instruction fetch requests
- **Frontend**: Handles instruction fetching and basic decoding
- **Warp Frontend**: Manages per-warp instruction flow

#### 2. Execution Pipeline
- **Decode Pipe**: Pipeline stage for instruction decoding
- **Instruction Decoder**: RISC-V instruction decoding
- **Scoreboard**: Dependency tracking and hazard detection
- **Register File**: General-purpose and vector registers
- **SIMT Stack**: Single Instruction Multiple Thread stack management

#### 3. Execution Units
- **ALU Issue**: Integer instruction issue logic
- **ALU Execution**: Integer arithmetic and logic operations
- **FPU Issue**: Floating-point instruction issue logic
- **FPU Execution**: Floating-point arithmetic operations

#### 4. Memory System
- **I-Cache**: Instruction cache with TileLink interface
- **G-Cache**: GPU cache for data access
- **TLB**: Translation Lookaside Buffer for address translation
- **PTW**: Page Table Walker for virtual memory support

## Memory Hierarchy

### Cache System
- **L1 I-Cache**: Instruction cache
- **L1 D-Cache**: Data cache
- **Vector Register File**: Dedicated vector register storage

### Memory Interface
- **TileLink Protocol**: Used for all cache coherence
- **External Memory**: DDR memory interface
- **Virtual Memory**: Full virtual memory support with TLB and PTW

## Key Features

### RISC-V Vector Extension Support
- Complete RVV instruction set implementation
- Vector register file with configurable size
- Vector execution units for parallel processing

### TileLink Integration
- Unified memory interface across all components
- Cache coherence support
- Scalable interconnect design

### Modular Design
- Clear separation of concerns
- Configurable parameters for different use cases
- Extensible architecture for future enhancements

## Configuration Parameters

The system supports extensive parameterization:

- **Warp Configuration**: Number of warps, threads per warp
- **Memory Configuration**: Cache sizes, memory bandwidth
- **Vector Configuration**: Vector register file size, vector length
- **Pipeline Configuration**: Pipeline depth, issue width

## Development Status

### Completed Components
- Core execution pipeline
- Basic memory system
- TileLink integration
- Vector extension support
- PTW-DCache integration

### Future Enhancements
- Graphics rendering pipeline
- Advanced scheduling algorithms
- Power management features
- Additional instruction set support 
