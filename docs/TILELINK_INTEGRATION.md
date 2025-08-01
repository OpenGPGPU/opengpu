# TileLink Integration Guide

## Overview

OPENGPU uses the TileLink protocol for all memory interfaces to provide better cache coherence support and unified memory access across all components.

## TileLink Protocol

### What is TileLink?

TileLink is a cache-coherent interconnect protocol that provides:
- **Cache Coherence**: Automatic cache coherence across multiple agents
- **Scalability**: Supports multiple masters and slaves
- **Flexibility**: Configurable channel types and capabilities
- **Performance**: Low-latency, high-bandwidth communication

### TileLink Channels

TileLink supports several channel types:

1. **A Channel (Address)**: Memory requests (Get, Put, etc.)
2. **B Channel (Probe)**: Cache coherence probes
3. **C Channel (Release)**: Cache line releases
4. **D Channel (Grant)**: Memory responses
5. **E Channel (GrantAck)**: Response acknowledgments

## OPENGPU TileLink Implementation

### Memory Components Using TileLink

#### 1. Instruction Cache (I-Cache)
```scala
class ICacheInterface(parameter: ICacheParameter) extends Bundle {
  val instructionFetchTileLink: TLLink = new TLLink(parameter.instructionFetchParameter)
  val itimTileLink: Option[TLLink] = parameter.itimParameter.map(p => Flipped(new TLLink(p)))
}
```

#### 2. Data Cache (D-Cache)
```scala
class DMemoryIO(parameter: DCacheParameter) extends Bundle {
  val tilelink = new TLLink(
    TLLinkParameter(
      addressWidth = parameter.paddrBits,
      sourceWidth = 4,
      sinkWidth = 4,
      dataWidth = parameter.dataWidth,
      sizeWidth = 3,
      hasBCEChannels = false
    )
  )
}
```

### TileLink Configuration

#### Basic Parameters
- **Address Width**: Physical address width (32-56 bits)
- **Data Width**: Data bus width (64 bits)
- **Source Width**: Source ID width for request tracking
- **Sink Width**: Sink ID width for response routing
- **Size Width**: Burst size encoding width

#### Channel Configuration
- **BCE Channels**: Broadcast coherence channels (disabled for simplicity)
- **Cache Coherence**: Automatic coherence management
- **Atomic Operations**: Support for atomic memory operations

## Memory Access Flow

### 1. Instruction Fetch
```
Frontend → I-Cache → TileLink → External Memory
```

### 2. Data Access
```
ALU/FPU → G-Cache → TileLink → External Memory
```

### 3. Page Table Walk
```
PTW → G-Cache → TileLink → External Memory
```

## Implementation Details

### TileLink Interface Definition

```scala
class TLLink(parameter: TLLinkParameter) extends Bundle {
  val a = Decoupled(new TLChannelA(parameter))
  val b = Flipped(Decoupled(new TLChannelB(parameter)))
  val c = Decoupled(new TLChannelC(parameter))
  val d = Flipped(Decoupled(new TLChannelD(parameter)))
  val e = Decoupled(new TLChannelE(parameter))
}
```

### Memory Request Types

#### Get (Read)
```scala
// Read request
val getRequest = Wire(new TLChannelA)
getRequest.opcode := OpCode.Get
getRequest.address := address
getRequest.size := size
```

#### Put (Write)
```scala
// Write request
val putRequest = Wire(new TLChannelA)
putRequest.opcode := OpCode.PutFullData
putRequest.address := address
putRequest.data := data
```

#### AccessAckData (Response)
```scala
// Read response
val response = Wire(new TLChannelD)
response.opcode := OpCode.AccessAckData
response.data := readData
response.source := requestSource
```

## Benefits of TileLink Integration

### 1. Unified Memory Interface
- All memory components use the same protocol
- Simplified interconnect design
- Consistent memory access patterns

### 2. Cache Coherence
- Automatic cache line management
- No manual coherence tracking required
- Support for multiple cache levels

### 3. Scalability
- Easy to add new memory components
- Configurable bandwidth and latency
- Support for complex memory hierarchies

### 4. Performance
- Low-latency communication
- High-bandwidth data transfer
- Efficient request/response handling

## Testing and Verification

### TileLink Test Components

#### 1. Memory Model
- TileLink-compliant memory model
- Configurable latency and bandwidth
- Support for all TileLink operations

#### 2. Cache Coherence Tests
- Multi-cache coherence verification
- Atomic operation testing
- Memory ordering validation

#### 3. Performance Tests
- Bandwidth measurement
- Latency analysis
- Cache hit/miss statistics

## Migration from AXI4

### Key Changes
1. **Protocol Change**: AXI4 → TileLink
2. **Interface Updates**: All memory interfaces updated
3. **Coherence Support**: Added automatic cache coherence
4. **Testing Updates**: New test infrastructure

### Benefits Achieved
- **Simplified Design**: Unified memory interface
- **Better Coherence**: Automatic cache management
- **Improved Performance**: Lower latency communication
- **Enhanced Scalability**: Easier to extend

## Future Enhancements

### Planned Improvements
1. **Advanced Coherence**: More sophisticated coherence protocols
2. **Performance Optimization**: Bandwidth and latency improvements
3. **Power Management**: Dynamic power scaling
4. **Debug Support**: Enhanced debugging capabilities

### Integration with External Systems
1. **CPU Integration**: TileLink interface for CPU-GPU communication
2. **External Memory**: DDR controller with TileLink interface
3. **I/O Devices**: Peripheral devices with TileLink support 