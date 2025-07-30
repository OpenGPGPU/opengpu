// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2024 OpenGPU Contributors

package ogpu.vector

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.SerializableModule
import ogpu.core._
import ogpu.fpu._

/** Vector FPU Execution Interface
  *
  * Defines the interface for vector floating-point unit execution
  */
class VectorFPUExecutionInterface(parameter: VectorParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())

  // Input from Issue stage
  val in = Flipped(DecoupledIO(new VectorFPUOperandBundle(parameter)))

  // Execution results output
  val out = DecoupledIO(new VectorResultBundle(parameter))

  // Vector mask for conditional execution
  val mask = Input(Vec(parameter.threadNum, Bool()))
}

/** Vector FPU Operand Bundle
  *
  * Contains operands for vector floating-point operations
  */
class VectorFPUOperandBundle(parameter: VectorParameter) extends Bundle {
  val warpID = UInt(log2Ceil(parameter.warpNum).W)
  val pc = UInt(32.W)
  val rd = UInt(5.W)
  val rs1Data = Vec(parameter.threadNum, UInt(parameter.xLen.W))
  val rs2Data = Vec(parameter.threadNum, UInt(parameter.xLen.W))
  val rs3Data = Vec(parameter.threadNum, UInt(parameter.xLen.W))
  val imm = UInt(32.W)
  val funct3 = UInt(3.W)
  val funct7 = UInt(7.W)
  val isRVC = Bool()
  val vectorOp = UInt(4.W) // Vector operation type
  val vectorWidth = UInt(2.W) // Vector element width (16, 32, 64)
  val vectorLength = UInt(8.W) // Vector length
  val fpuOp = UInt(5.W) // FPU operation code
  val fpuFmt = UInt(2.W) // FPU format (16, 32, 64)
  val roundingMode = UInt(3.W) // Rounding mode
}

/** Vector FPU Execution Module
  *
  * Handles vector floating-point operations
  */
@instantiable
class VectorFPUExecution(val parameter: VectorParameter)
    extends FixedIORawModule(new VectorFPUExecutionInterface(parameter))
    with SerializableModule[VectorParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // Vector FPU instances for different element widths
  val vectorFPU16 = Module(new VectorFPU(parameter, 16))
  val vectorFPU32 = Module(new VectorFPU(parameter, 32))
  val vectorFPU64 = Module(new VectorFPU(parameter, 64))

  // Pipeline control
  io.in.ready := true.B // Always ready to accept new instruction

  // Output registers
  val outValidReg = RegInit(false.B)
  val outResultReg = RegInit(Vec(parameter.threadNum, 0.U(parameter.xLen.W)))
  val outWarpIDReg = RegInit(0.U(log2Ceil(parameter.warpNum).W))
  val outRdReg = RegInit(0.U(5.W))
  val outExceptionReg = RegInit(false.B)
  val outFflagsReg = RegInit(0.U(5.W))

  // Vector FPU operation execution
  when(io.in.valid) {
    outValidReg := true.B
    outWarpIDReg := io.in.bits.warpID
    outRdReg := io.in.bits.rd
    outExceptionReg := false.B

    // Select appropriate FPU based on vector width
    val result = Wire(Vec(parameter.threadNum, UInt(parameter.xLen.W)))
    val fflags = Wire(UInt(5.W))

    switch(io.in.bits.vectorWidth) {
      is(0.U) { // 16-bit elements (half precision)
        vectorFPU16.io.in1 := io.in.bits.rs1Data
        vectorFPU16.io.in2 := io.in.bits.rs2Data
        vectorFPU16.io.in3 := io.in.bits.rs3Data
        vectorFPU16.io.fpuOp := io.in.bits.fpuOp
        vectorFPU16.io.fpuFmt := io.in.bits.fpuFmt
        vectorFPU16.io.roundingMode := io.in.bits.roundingMode
        vectorFPU16.io.mask := io.mask
        result := vectorFPU16.io.out
        fflags := vectorFPU16.io.fflags
      }
      is(1.U) { // 32-bit elements (single precision)
        vectorFPU32.io.in1 := io.in.bits.rs1Data
        vectorFPU32.io.in2 := io.in.bits.rs2Data
        vectorFPU32.io.in3 := io.in.bits.rs3Data
        vectorFPU32.io.fpuOp := io.in.bits.fpuOp
        vectorFPU32.io.fpuFmt := io.in.bits.fpuFmt
        vectorFPU32.io.roundingMode := io.in.bits.roundingMode
        vectorFPU32.io.mask := io.mask
        result := vectorFPU32.io.out
        fflags := vectorFPU32.io.fflags
      }
      is(2.U) { // 64-bit elements (double precision)
        vectorFPU64.io.in1 := io.in.bits.rs1Data
        vectorFPU64.io.in2 := io.in.bits.rs2Data
        vectorFPU64.io.in3 := io.in.bits.rs3Data
        vectorFPU64.io.fpuOp := io.in.bits.fpuOp
        vectorFPU64.io.fpuFmt := io.in.bits.fpuFmt
        vectorFPU64.io.roundingMode := io.in.bits.roundingMode
        vectorFPU64.io.mask := io.mask
        result := vectorFPU64.io.out
        fflags := vectorFPU64.io.fflags
      }
      is(3.U) { // 64-bit elements (double precision) - same as 2.U
        vectorFPU64.io.in1 := io.in.bits.rs1Data
        vectorFPU64.io.in2 := io.in.bits.rs2Data
        vectorFPU64.io.in3 := io.in.bits.rs3Data
        vectorFPU64.io.fpuOp := io.in.bits.fpuOp
        vectorFPU64.io.fpuFmt := io.in.bits.fpuFmt
        vectorFPU64.io.roundingMode := io.in.bits.roundingMode
        vectorFPU64.io.mask := io.mask
        result := vectorFPU64.io.out
        fflags := vectorFPU64.io.fflags
      }
    }

    outResultReg := result
    outFflagsReg := fflags
  }.otherwise {
    outValidReg := false.B
  }

  // Connect output signals
  io.out.valid := outValidReg
  io.out.bits.result := outResultReg
  io.out.bits.warpID := outWarpIDReg
  io.out.bits.rd := outRdReg
  io.out.bits.exception := outExceptionReg
  io.out.bits.fflags := outFflagsReg
}

/** Vector FPU Module
  *
  * Performs vector floating-point operations on elements of specified width
  */
class VectorFPU(parameter: VectorParameter, elementWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in1 = Input(Vec(parameter.threadNum, UInt(parameter.xLen.W)))
    val in2 = Input(Vec(parameter.threadNum, UInt(parameter.xLen.W)))
    val in3 = Input(Vec(parameter.threadNum, UInt(parameter.xLen.W)))
    val fpuOp = Input(UInt(5.W))
    val fpuFmt = Input(UInt(2.W))
    val roundingMode = Input(UInt(3.W))
    val mask = Input(Vec(parameter.threadNum, Bool()))
    val out = Output(Vec(parameter.threadNum, UInt(parameter.xLen.W)))
    val fflags = Output(UInt(5.W))
  })

  val elementsPerWord = parameter.xLen / elementWidth
  val result = Wire(Vec(parameter.threadNum, UInt(parameter.xLen.W)))
  val fflags = Wire(UInt(5.W))

  // FPU instances for each thread
  val fpus =
    Array.tabulate(parameter.threadNum)(_ => Module(new FPU(new OGPUParameter(Set("rv_f"), false, false))))

  // Process each thread
  for (thread <- 0 until parameter.threadNum) {
    val threadResult = Wire(Vec(elementsPerWord, UInt(elementWidth.W)))

    // Process each element in the word
    for (elem <- 0 until elementsPerWord) {
      val elemStart = elem * elementWidth
      val elemEnd = elemStart + elementWidth - 1

      val op1 = io.in1(thread)(elemEnd, elemStart)
      val op2 = io.in2(thread)(elemEnd, elemStart)
      val op3 = io.in3(thread)(elemEnd, elemStart)

      // FPU operation
      val elemResult = Wire(UInt(elementWidth.W))

      when(io.mask(thread)) {
        // Connect FPU for this thread
        fpus(thread).io.clk_i := clock
        fpus(thread).io.rst_ni := !reset.asBool
        fpus(thread).io.op_a := op1
        fpus(thread).io.op_b := op2
        fpus(thread).io.op_c := op3
        fpus(thread).io.rnd_mode := io.roundingMode
        fpus(thread).io.op := io.fpuOp
        fpus(thread).io.op_mod := false.B
        fpus(thread).io.src_fmt := io.fpuFmt
        fpus(thread).io.dst_fmt := io.fpuFmt
        fpus(thread).io.int_fmt := io.fpuFmt
        fpus(thread).io.vectorial_op := true.B
        fpus(thread).io.tag_i := thread.U
        fpus(thread).io.in_valid := true.B
        fpus(thread).io.flush := false.B
        fpus(thread).io.out_ready := true.B

        elemResult := fpus(thread).io.result
      }.otherwise {
        elemResult := 0.U
      }

      threadResult(elem) := elemResult
    }

    // Combine elements into result word
    result(thread) := threadResult.asUInt
  }

  // Combine fflags from all threads
  val combinedFflags = Wire(UInt(5.W))
  combinedFflags := 0.U
  for (thread <- 0 until parameter.threadNum) {
    combinedFflags := combinedFflags | fpus(thread).io.status
  }

  io.out := result
  io.fflags := combinedFflags
}
