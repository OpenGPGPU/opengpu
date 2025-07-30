// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2024 OpenGPU Contributors

package ogpu.vector

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.SerializableModule

/** Vector Controller Interface
  *
  * Defines the interface for vector controller that coordinates vector operations
  */
class VectorControllerInterface(parameter: VectorParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())

  // Input from Issue stage
  val in = Flipped(DecoupledIO(new VectorControllerOperandBundle(parameter)))

  // Vector execution units
  val aluExecution = new VectorALUExecutionInterface(parameter)
  val fpuExecution = new VectorFPUExecutionInterface(parameter)
  val memoryExecution = new VectorMemoryExecutionInterface(parameter)
  val shuffleExecution = new VectorShuffleExecutionInterface(parameter)

  // Execution results output
  val out = DecoupledIO(new VectorResultBundle(parameter))

  // Vector mask for conditional execution
  val mask = Input(Vec(parameter.threadNum, Bool()))
}

/** Vector Controller Operand Bundle
  *
  * Contains operands for vector controller operations
  */
class VectorControllerOperandBundle(parameter: VectorParameter) extends Bundle {
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
  val executionUnit = UInt(2.W) // 0=ALU, 1=FPU, 2=Memory, 3=Shuffle
  val fpuOp = UInt(5.W) // FPU operation code
  val fpuFmt = UInt(2.W) // FPU format (16, 32, 64)
  val roundingMode = UInt(3.W) // Rounding mode
  val isLoad = Bool() // True for load, false for store
  val isStrided = Bool() // True for strided access
  val isIndexed = Bool() // True for indexed access
  val stride = UInt(32.W) // Stride value for strided access
  val shuffleType = UInt(3.W) // Shuffle operation type
  val shuffleMode = UInt(2.W) // Shuffle mode
}

/** Vector Controller Module
  *
  * Coordinates vector operations across different execution units
  */
@instantiable
class VectorController(val parameter: VectorParameter)
    extends FixedIORawModule(new VectorControllerInterface(parameter))
    with SerializableModule[VectorParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // Vector execution units
  val vectorALU = Module(new VectorALUExecution(parameter))
  val vectorFPU = Module(new VectorFPUExecution(parameter))
  val vectorMemory = Module(new VectorMemoryExecution(parameter))
  val vectorShuffle = Module(new VectorShuffleExecution(parameter))

  // Pipeline control
  io.in.ready := true.B // Always ready to accept new instruction

  // Output registers
  val outValidReg = RegInit(false.B)
  val outResultReg = RegInit(Vec(parameter.threadNum, 0.U(parameter.xLen.W)))
  val outWarpIDReg = RegInit(0.U(log2Ceil(parameter.warpNum).W))
  val outRdReg = RegInit(0.U(5.W))
  val outExceptionReg = RegInit(false.B)
  val outFflagsReg = RegInit(0.U(5.W))

  // Connect clock and reset to all execution units
  vectorALU.io.clock := io.clock
  vectorALU.io.reset := io.reset
  vectorFPU.io.clock := io.clock
  vectorFPU.io.reset := io.reset
  vectorMemory.io.clock := io.clock
  vectorMemory.io.reset := io.reset
  vectorShuffle.io.clock := io.clock
  vectorShuffle.io.reset := io.reset

  // Connect mask to all execution units
  vectorALU.io.mask := io.mask
  vectorFPU.io.mask := io.mask
  vectorMemory.io.mask := io.mask
  vectorShuffle.io.mask := io.mask

  // Vector operation execution
  when(io.in.valid) {
    outValidReg := true.B
    outWarpIDReg := io.in.bits.warpID
    outRdReg := io.in.bits.rd
    outExceptionReg := false.B

    // Route operation to appropriate execution unit
    switch(io.in.bits.executionUnit) {
      is(0.U) { // ALU
        // Connect ALU operands
        vectorALU.io.in.bits.warpID := io.in.bits.warpID
        vectorALU.io.in.bits.pc := io.in.bits.pc
        vectorALU.io.in.bits.rd := io.in.bits.rd
        vectorALU.io.in.bits.rs1Data := io.in.bits.rs1Data
        vectorALU.io.in.bits.rs2Data := io.in.bits.rs2Data
        vectorALU.io.in.bits.rs3Data := io.in.bits.rs3Data
        vectorALU.io.in.bits.imm := io.in.bits.imm
        vectorALU.io.in.bits.funct3 := io.in.bits.funct3
        vectorALU.io.in.bits.funct7 := io.in.bits.funct7
        vectorALU.io.in.bits.isRVC := io.in.bits.isRVC
        vectorALU.io.in.bits.vectorOp := io.in.bits.vectorOp
        vectorALU.io.in.bits.vectorWidth := io.in.bits.vectorWidth
        vectorALU.io.in.bits.vectorLength := io.in.bits.vectorLength
        vectorALU.io.in.valid := true.B

        // Get ALU result
        outResultReg := vectorALU.io.out.bits.result
        outFflagsReg := vectorALU.io.out.bits.fflags
      }
      is(1.U) { // FPU
        // Connect FPU operands
        vectorFPU.io.in.bits.warpID := io.in.bits.warpID
        vectorFPU.io.in.bits.pc := io.in.bits.pc
        vectorFPU.io.in.bits.rd := io.in.bits.rd
        vectorFPU.io.in.bits.rs1Data := io.in.bits.rs1Data
        vectorFPU.io.in.bits.rs2Data := io.in.bits.rs2Data
        vectorFPU.io.in.bits.rs3Data := io.in.bits.rs3Data
        vectorFPU.io.in.bits.imm := io.in.bits.imm
        vectorFPU.io.in.bits.funct3 := io.in.bits.funct3
        vectorFPU.io.in.bits.funct7 := io.in.bits.funct7
        vectorFPU.io.in.bits.isRVC := io.in.bits.isRVC
        vectorFPU.io.in.bits.vectorOp := io.in.bits.vectorOp
        vectorFPU.io.in.bits.vectorWidth := io.in.bits.vectorWidth
        vectorFPU.io.in.bits.vectorLength := io.in.bits.vectorLength
        vectorFPU.io.in.bits.fpuOp := io.in.bits.fpuOp
        vectorFPU.io.in.bits.fpuFmt := io.in.bits.fpuFmt
        vectorFPU.io.in.bits.roundingMode := io.in.bits.roundingMode
        vectorFPU.io.in.valid := true.B

        // Get FPU result
        outResultReg := vectorFPU.io.out.bits.result
        outFflagsReg := vectorFPU.io.out.bits.fflags
      }
      is(2.U) { // Memory
        // Connect Memory operands
        vectorMemory.io.in.bits.warpID := io.in.bits.warpID
        vectorMemory.io.in.bits.pc := io.in.bits.pc
        vectorMemory.io.in.bits.rd := io.in.bits.rd
        vectorMemory.io.in.bits.rs1Data := io.in.bits.rs1Data
        vectorMemory.io.in.bits.rs2Data := io.in.bits.rs2Data
        vectorMemory.io.in.bits.rs3Data := io.in.bits.rs3Data
        vectorMemory.io.in.bits.imm := io.in.bits.imm
        vectorMemory.io.in.bits.funct3 := io.in.bits.funct3
        vectorMemory.io.in.bits.funct7 := io.in.bits.funct7
        vectorMemory.io.in.bits.isRVC := io.in.bits.isRVC
        vectorMemory.io.in.bits.vectorOp := io.in.bits.vectorOp
        vectorMemory.io.in.bits.vectorWidth := io.in.bits.vectorWidth
        vectorMemory.io.in.bits.vectorLength := io.in.bits.vectorLength
        vectorMemory.io.in.bits.isLoad := io.in.bits.isLoad
        vectorMemory.io.in.bits.isStrided := io.in.bits.isStrided
        vectorMemory.io.in.bits.isIndexed := io.in.bits.isIndexed
        vectorMemory.io.in.bits.stride := io.in.bits.stride
        vectorMemory.io.in.valid := true.B

        // Get Memory result
        outResultReg := vectorMemory.io.out.bits.result
        outFflagsReg := vectorMemory.io.out.bits.fflags
      }
      is(3.U) { // Shuffle
        // Connect Shuffle operands
        vectorShuffle.io.in.bits.warpID := io.in.bits.warpID
        vectorShuffle.io.in.bits.pc := io.in.bits.pc
        vectorShuffle.io.in.bits.rd := io.in.bits.rd
        vectorShuffle.io.in.bits.rs1Data := io.in.bits.rs1Data
        vectorShuffle.io.in.bits.rs2Data := io.in.bits.rs2Data
        vectorShuffle.io.in.bits.rs3Data := io.in.bits.rs3Data
        vectorShuffle.io.in.bits.imm := io.in.bits.imm
        vectorShuffle.io.in.bits.funct3 := io.in.bits.funct3
        vectorShuffle.io.in.bits.funct7 := io.in.bits.funct7
        vectorShuffle.io.in.bits.isRVC := io.in.bits.isRVC
        vectorShuffle.io.in.bits.vectorOp := io.in.bits.vectorOp
        vectorShuffle.io.in.bits.vectorWidth := io.in.bits.vectorWidth
        vectorShuffle.io.in.bits.vectorLength := io.in.bits.vectorLength
        vectorShuffle.io.in.bits.shuffleType := io.in.bits.shuffleType
        vectorShuffle.io.in.bits.shuffleMode := io.in.bits.shuffleMode
        vectorShuffle.io.in.valid := true.B

        // Get Shuffle result
        outResultReg := vectorShuffle.io.out.bits.result
        outFflagsReg := vectorShuffle.io.out.bits.fflags
      }
    }
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

  // Connect execution unit interfaces
  io.aluExecution := vectorALU.io
  io.fpuExecution := vectorFPU.io
  io.memoryExecution := vectorMemory.io
  io.shuffleExecution := vectorShuffle.io
}
