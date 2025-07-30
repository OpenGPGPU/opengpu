# FPU Module

This directory contains the FPU (Floating Point Unit) implementation using the fpnew library.

## Files

- `FPU.scala`: Main Chisel FPU module that wraps the fpnew SystemVerilog implementation
- `fpnew_wrapper.sv`: SystemVerilog wrapper for fpnew_top module
- `Makefile`: Build script to combine all fpnew source files into `combined.sv`
- `FPUTest.scala`: Simple test to verify FPU module generation

## Build System Integration

The FPU module is integrated into the mill build system with automatic dependency tracking:

### Automatic Regeneration

The build system automatically tracks changes to:
- fpnew source files in `depends/fpnew/src/`
- `Makefile` in `src/fpu/`
- `fpnew_wrapper.sv` in `src/fpu/`

When any of these files change, the `combined.sv` file will be automatically regenerated.

# Run FPU test
mill ogpu.test.runMain ogpu.fpu.FPUTest
```

## Usage

The FPU module can be instantiated in your Chisel code:

```scala
import ogpu.fpu._
import ogpu.core._

val parameter = OGPUParameter(
  instructionSets = Set("rv_i", "rv_f"),
  pipelinedMul = true,
  fenceIFlushDCache = false,
  warpNum = 8,
  minFLen = 32,
  vLen = 1024,
  xLen = 32
)

val fpu = Module(new FPU(parameter))
```

## Interface

The FPU module provides the following interface:

- `op_a`, `op_b`, `op_c`: Input operands
- `rnd_mode`: Rounding mode
- `op`: Operation code
- `op_mod`: Operation modifier
- `src_fmt`, `dst_fmt`, `int_fmt`: Format specifications
- `vectorial_op`: Vector operation flag
- `tag_i`: Input tag
- `in_valid`, `in_ready`: Handshake signals
- `flush`: Flush signal
- `result`: Output result
- `status`: Status flags
- `tag_o`: Output tag
- `out_valid`, `out_ready`: Output handshake signals
- `busy`: Busy indicator

## Dependencies

The FPU module depends on the fpnew library located in `depends/fpnew/`. The build system automatically tracks changes to the fpnew source files and regenerates the combined Verilog file when needed. 
