# Vector Processing Units

This directory contains vector processing units for the OpenGPU project, designed to handle vector operations efficiently across multiple threads and warps.

## Overview

The vector processing units are designed to support:
- Vector arithmetic and logic operations
- Vector floating-point operations
- Vector memory access operations
- Vector shuffle and permutation operations

## Components

### 1. VectorDecoder.scala
The main vector instruction decoder that handles vector instruction decoding and routing.

### 2. VectorALU.scala
Vector Arithmetic Logic Unit that performs:
- Vector addition, subtraction, multiplication
- Vector logical operations (AND, OR, XOR)
- Vector shift operations
- Vector comparison operations
- Support for different element widths (8, 16, 32, 64 bits)

### 3. VectorFPU.scala
Vector Floating-Point Unit that performs:
- Vector floating-point addition, subtraction, multiplication, division
- Vector floating-point comparison operations
- Support for different precision formats (half, single, double)
- Proper rounding mode handling
- Exception flag generation

### 4. VectorMemory.scala
Vector Memory Access Unit that handles:
- Vector load operations
- Vector store operations
- Strided memory access
- Indexed memory access
- Unit stride memory access
- Memory alignment and masking

### 5. VectorShuffle.scala
Vector Shuffle Unit that performs:
- Vector element permutation
- Vector shuffle operations
- Vector interleave/deinterleave
- Vector rotation
- Vector reversal
- Vector element duplication
- Vector element extraction

### 6. VectorController.scala
Main vector controller that:
- Coordinates operations across different vector units
- Routes instructions to appropriate execution units
- Manages vector operation scheduling
- Handles vector operation dependencies

## Architecture

### Vector Operation Flow

1. **Instruction Decode**: VectorDecoder decodes vector instructions
2. **Operand Preparation**: Operands are prepared for vector operations
3. **Execution Unit Selection**: VectorController routes to appropriate unit
4. **Vector Operation Execution**: Selected unit performs the operation
5. **Result Collection**: Results are collected and formatted
6. **Writeback**: Results are written back to register file

### Vector Element Processing

Each vector operation processes multiple elements in parallel:
- **Element Width**: 8, 16, 32, or 64 bits per element
- **Vector Length**: Configurable vector length
- **Thread Parallelism**: Operations across multiple threads
- **Warp Parallelism**: Operations across multiple warps

### Masking Support

All vector operations support conditional execution through masks:
- **Thread Mask**: Controls which threads participate in operation
- **Element Mask**: Controls which elements are processed
- **Predication**: Conditional execution based on mask values

## Usage

### Basic Vector ALU Operation

```scala
// Create vector ALU execution unit
val vectorALU = Module(new VectorALUExecution(parameter))

// Connect inputs
vectorALU.io.in.bits.rs1Data := sourceData
vectorALU.io.in.bits.rs2Data := operandData
vectorALU.io.in.bits.funct3 := operationCode
vectorALU.io.in.bits.vectorWidth := elementWidth
vectorALU.io.in.valid := true.B

// Get results
val result = vectorALU.io.out.bits.result
```

### Vector Memory Access

```scala
// Create vector memory unit
val vectorMemory = Module(new VectorMemoryExecution(parameter))

// Configure memory access
vectorMemory.io.in.bits.isLoad := true.B
vectorMemory.io.in.bits.isStrided := false.B
vectorMemory.io.in.bits.rs1Data := baseAddress
vectorMemory.io.in.bits.vectorWidth := elementWidth
vectorMemory.io.in.valid := true.B

// Handle memory requests
val memRead = vectorMemory.io.memRead
val memWrite = vectorMemory.io.memWrite
```

### Vector Shuffle Operation

```scala
// Create vector shuffle unit
val vectorShuffle = Module(new VectorShuffleExecution(parameter))

// Configure shuffle operation
vectorShuffle.io.in.bits.shuffleType := 0.U // Permute
vectorShuffle.io.in.bits.shuffleMode := 0.U // Within thread
vectorShuffle.io.in.bits.rs1Data := sourceData
vectorShuffle.io.in.bits.rs2Data := indexData
vectorShuffle.io.in.valid := true.B

// Get shuffled result
val shuffledResult = vectorShuffle.io.out.bits.result
```

## Configuration

### Parameter Configuration

The vector units use the `OGPUDecoderParameter` for configuration:

```scala
case class OGPUDecoderParameter(
  xLen: Int,           // Scalar register width
  threadNum: Int,      // Number of threads per warp
  warpNum: Int,        // Number of warps
  // ... other parameters
)
```

### Element Width Configuration

Vector operations support different element widths:
- **8-bit**: Byte operations
- **16-bit**: Half-word operations  
- **32-bit**: Word operations
- **64-bit**: Double-word operations

### Vector Length Configuration

Vector length is configurable and affects:
- Number of elements processed per operation
- Memory access patterns
- Shuffle operation complexity

## Performance Considerations

### Pipelining
- All vector units are pipelined for high throughput
- Single-cycle operations for simple vector operations
- Multi-cycle operations for complex operations (e.g., division)

### Parallelism
- Thread-level parallelism across multiple threads
- Element-level parallelism within each thread
- Warp-level parallelism across multiple warps

### Memory Bandwidth
- Optimized for high memory bandwidth utilization
- Support for different memory access patterns
- Efficient handling of memory alignment requirements

## Testing

### Unit Tests
Each vector unit includes comprehensive unit tests:
- Basic functionality tests
- Edge case tests
- Performance tests
- Integration tests

### Simulation
Vector units can be simulated using:
- Chisel testers
- Verilator simulation
- FPGA prototyping

## Future Enhancements

### Planned Features
- Advanced vector operations (FFT, matrix operations)
- Vector reduction operations
- Vector scatter/gather operations
- Vector transcendental functions
- Vector compression/decompression

### Performance Optimizations
- Advanced pipelining techniques
- Dynamic vector length adjustment
- Adaptive element width selection
- Cache-aware memory access patterns

## Contributing

When contributing to the vector processing units:

1. Follow the existing code style and patterns
2. Add comprehensive documentation
3. Include unit tests for new functionality
4. Update this README for new features
5. Ensure compatibility with existing vector operations

## License

This code is licensed under the Apache-2.0 License. 