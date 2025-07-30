// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2024 OpenGPU Contributors

package ogpu.vector

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.SerializableModule

/** Vector ALU Execution Interface
  *
  * Defines the interface for vector arithmetic logic unit execution
  */
class VectorALUExecutionInterface(parameter: VectorParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())

  // Input from Issue stage
  val in = Flipped(DecoupledIO(new VectorALUOperandBundle(parameter)))

  // Execution results output
  val out = DecoupledIO(new VectorResultBundle(parameter))

  // Vector mask for conditional execution
  val mask = Input(Vec(parameter.threadNum, Bool()))
}

/** Vector ALU Operand Bundle
  *
  * Contains operands for vector ALU operations
  */
class VectorALUOperandBundle(parameter: VectorParameter) extends Bundle {
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
  val vectorWidth = UInt(2.W) // Vector element width (8, 16, 32, 64)
  val vectorLength = UInt(8.W) // Vector length
}

/** Vector ALU Execution Module
  *
  * Handles vector arithmetic and logic operations
  */
@instantiable
class VectorALUExecution(val parameter: VectorParameter)
    extends FixedIORawModule(new VectorALUExecutionInterface(parameter))
    with SerializableModule[VectorParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // Vector ALU instances for different element widths
  val vectorALU8 = Module(new VectorALU(parameter, 8))
  val vectorALU16 = Module(new VectorALU(parameter, 16))
  val vectorALU32 = Module(new VectorALU(parameter, 32))
  val vectorALU64 = Module(new VectorALU(parameter, 64))

  // Pipeline control
  io.in.ready := true.B // Always ready to accept new instruction

  // Output registers
  val outValidReg = RegInit(false.B)
  val outResultReg = RegInit(Vec(parameter.threadNum, 0.U(parameter.xLen.W)))
  val outWarpIDReg = RegInit(0.U(log2Ceil(parameter.warpNum).W))
  val outRdReg = RegInit(0.U(5.W))
  val outExceptionReg = RegInit(false.B)

  // Vector operation execution
  when(io.in.valid) {
    outValidReg := true.B
    outWarpIDReg := io.in.bits.warpID
    outRdReg := io.in.bits.rd
    outExceptionReg := false.B

    // Select appropriate ALU based on vector width
    val result = Wire(Vec(parameter.threadNum, UInt(parameter.xLen.W)))

    switch(io.in.bits.vectorWidth) {
      is(0.U) { // 8-bit elements
        vectorALU8.io.in1 := io.in.bits.rs1Data
        vectorALU8.io.in2 := io.in.bits.rs2Data
        vectorALU8.io.funct3 := io.in.bits.funct3
        vectorALU8.io.funct7 := io.in.bits.funct7
        vectorALU8.io.mask := io.mask
        result := vectorALU8.io.out
      }
      is(1.U) { // 16-bit elements
        vectorALU16.io.in1 := io.in.bits.rs1Data
        vectorALU16.io.in2 := io.in.bits.rs2Data
        vectorALU16.io.funct3 := io.in.bits.funct3
        vectorALU16.io.funct7 := io.in.bits.funct7
        vectorALU16.io.mask := io.mask
        result := vectorALU16.io.out
      }
      is(2.U) { // 32-bit elements
        vectorALU32.io.in1 := io.in.bits.rs1Data
        vectorALU32.io.in2 := io.in.bits.rs2Data
        vectorALU32.io.funct3 := io.in.bits.funct3
        vectorALU32.io.funct7 := io.in.bits.funct7
        vectorALU32.io.mask := io.mask
        result := vectorALU32.io.out
      }
      is(3.U) { // 64-bit elements
        vectorALU64.io.in1 := io.in.bits.rs1Data
        vectorALU64.io.in2 := io.in.bits.rs2Data
        vectorALU64.io.funct3 := io.in.bits.funct3
        vectorALU64.io.funct7 := io.in.bits.funct7
        vectorALU64.io.mask := io.mask
        result := vectorALU64.io.out
      }
    }

    outResultReg := result
  }.otherwise {
    outValidReg := false.B
  }

  // Connect output signals
  io.out.valid := outValidReg
  io.out.bits.result := outResultReg
  io.out.bits.warpID := outWarpIDReg
  io.out.bits.rd := outRdReg
  io.out.bits.exception := outExceptionReg
  io.out.bits.fflags := 0.U // Vector ALU does not set fflags
}

/** Vector ALU Module
  *
  * Performs vector arithmetic and logic operations on elements of specified width
  */
class VectorALU(parameter: VectorParameter, elementWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in1 = Input(Vec(parameter.threadNum, UInt(parameter.xLen.W)))
    val in2 = Input(Vec(parameter.threadNum, UInt(parameter.xLen.W)))
    val funct3 = Input(UInt(3.W))
    val funct7 = Input(UInt(7.W))
    val mask = Input(Vec(parameter.threadNum, Bool()))
    val out = Output(Vec(parameter.threadNum, UInt(parameter.xLen.W)))
  })

  val elementsPerWord = parameter.xLen / elementWidth
  val result = Wire(Vec(parameter.threadNum, UInt(parameter.xLen.W)))

  // Process each thread
  for (thread <- 0 until parameter.threadNum) {
    val threadResult = Wire(Vec(elementsPerWord, UInt(elementWidth.W)))

    // Process each element in the word
    for (elem <- 0 until elementsPerWord) {
      val elemStart = elem * elementWidth
      val elemEnd = elemStart + elementWidth - 1

      val op1 = io.in1(thread)(elemEnd, elemStart)
      val op2 = io.in2(thread)(elemEnd, elemStart)

      // Vector ALU operation based on funct3 and funct7
      val elemResult = Wire(UInt(elementWidth.W))

      when(io.mask(thread)) {
        switch(io.funct3) {
          is(0.U) { // ADD
            elemResult := op1 + op2
          }
          is(1.U) { // SLL (Shift Left Logical)
            elemResult := op1 << op2(log2Ceil(elementWidth) - 1, 0)
          }
          is(2.U) { // SLT (Set Less Than)
            elemResult := Mux(op1.asSInt < op2.asSInt, 1.U, 0.U)
          }
          is(3.U) { // SLTU (Set Less Than Unsigned)
            elemResult := Mux(op1 < op2, 1.U, 0.U)
          }
          is(4.U) { // XOR
            elemResult := op1 ^ op2
          }
          is(5.U) { // SRL/SRA
            when(io.funct7(5)) { // SRA
              elemResult := (op1.asSInt >> op2(log2Ceil(elementWidth) - 1, 0)).asUInt
            }.otherwise { // SRL
              elemResult := op1 >> op2(log2Ceil(elementWidth) - 1, 0)
            }
          }
          is(6.U) { // OR
            elemResult := op1 | op2
          }
          is(7.U) { // AND
            elemResult := op1 & op2
          }
        }
      }.otherwise {
        elemResult := 0.U
      }

      threadResult(elem) := elemResult
    }

    // Combine elements into result word
    result(thread) := threadResult.asUInt
  }

  io.out := result
}
