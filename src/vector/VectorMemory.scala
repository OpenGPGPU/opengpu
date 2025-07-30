// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2024 OpenGPU Contributors

package ogpu.vector

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.SerializableModule

/** Vector Memory Execution Interface
  *
  * Defines the interface for vector memory access operations
  */
class VectorMemoryExecutionInterface(parameter: VectorParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())

  // Input from Issue stage
  val in = Flipped(DecoupledIO(new VectorMemoryOperandBundle(parameter)))

  // Memory interface
  val memRead = DecoupledIO(new VectorMemoryReadBundle(parameter))
  val memWrite = DecoupledIO(new VectorMemoryWriteBundle(parameter))

  // Execution results output
  val out = DecoupledIO(new VectorResultBundle(parameter))

  // Vector mask for conditional execution
  val mask = Input(Vec(parameter.threadNum, Bool()))
}

/** Vector Memory Operand Bundle
  *
  * Contains operands for vector memory operations
  */
class VectorMemoryOperandBundle(parameter: VectorParameter) extends Bundle {
  val warpID = UInt(log2Ceil(parameter.warpNum).W)
  val pc = UInt(32.W)
  val rd = UInt(5.W)
  val rs1Data = Vec(parameter.threadNum, UInt(parameter.xLen.W)) // Base address
  val rs2Data = Vec(parameter.threadNum, UInt(parameter.xLen.W)) // Offset or data
  val rs3Data = Vec(parameter.threadNum, UInt(parameter.xLen.W)) // Additional data
  val imm = UInt(32.W)
  val funct3 = UInt(3.W)
  val funct7 = UInt(7.W)
  val isRVC = Bool()
  val vectorOp = UInt(4.W) // Vector operation type
  val vectorWidth = UInt(2.W) // Vector element width (8, 16, 32, 64)
  val vectorLength = UInt(8.W) // Vector length
  val isLoad = Bool() // True for load, false for store
  val isStrided = Bool() // True for strided access
  val isIndexed = Bool() // True for indexed access
  val stride = UInt(32.W) // Stride value for strided access
}

/** Vector Memory Read Bundle
  *
  * Contains read request information
  */
class VectorMemoryReadBundle(parameter: VectorParameter) extends Bundle {
  val addr = Vec(parameter.threadNum, UInt(parameter.xLen.W))
  val size = UInt(2.W) // 0=byte, 1=half, 2=word, 3=double
  val mask = Vec(parameter.threadNum, Bool())
  val warpID = UInt(log2Ceil(parameter.warpNum).W)
  val rd = UInt(5.W)
}

/** Vector Memory Write Bundle
  *
  * Contains write request information
  */
class VectorMemoryWriteBundle(parameter: VectorParameter) extends Bundle {
  val addr = Vec(parameter.threadNum, UInt(parameter.xLen.W))
  val data = Vec(parameter.threadNum, UInt(parameter.xLen.W))
  val size = UInt(2.W) // 0=byte, 1=half, 2=word, 3=double
  val mask = Vec(parameter.threadNum, Bool())
  val warpID = UInt(log2Ceil(parameter.warpNum).W)
}

/** Vector Memory Execution Module
  *
  * Handles vector memory access operations
  */
@instantiable
class VectorMemoryExecution(val parameter: VectorParameter)
    extends FixedIORawModule(new VectorMemoryExecutionInterface(parameter))
    with SerializableModule[VectorParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // Vector Memory instances for different element widths
  val vectorMemory8 = Module(new VectorMemory(parameter, 8))
  val vectorMemory16 = Module(new VectorMemory(parameter, 16))
  val vectorMemory32 = Module(new VectorMemory(parameter, 32))
  val vectorMemory64 = Module(new VectorMemory(parameter, 64))

  // Pipeline control
  io.in.ready := true.B // Always ready to accept new instruction

  // Output registers
  val outValidReg = RegInit(false.B)
  val outResultReg = RegInit(Vec(parameter.threadNum, 0.U(parameter.xLen.W)))
  val outWarpIDReg = RegInit(0.U(log2Ceil(parameter.warpNum).W))
  val outRdReg = RegInit(0.U(5.W))
  val outExceptionReg = RegInit(false.B)

  // Memory request registers
  val memReadValidReg = RegInit(false.B)
  val memReadBundleReg = Reg(new VectorMemoryReadBundle(parameter))
  val memWriteValidReg = RegInit(false.B)
  val memWriteBundleReg = Reg(new VectorMemoryWriteBundle(parameter))

  // Vector Memory operation execution
  when(io.in.valid) {
    outValidReg := true.B
    outWarpIDReg := io.in.bits.warpID
    outRdReg := io.in.bits.rd
    outExceptionReg := false.B

    // Select appropriate Memory unit based on vector width
    val result = Wire(Vec(parameter.threadNum, UInt(parameter.xLen.W)))
    val memReadReq = Wire(Decoupled(new VectorMemoryReadBundle(parameter)))
    val memWriteReq = Wire(Decoupled(new VectorMemoryWriteBundle(parameter)))

    switch(io.in.bits.vectorWidth) {
      is(0.U) { // 8-bit elements
        vectorMemory8.io.in1 := io.in.bits.rs1Data
        vectorMemory8.io.in2 := io.in.bits.rs2Data
        vectorMemory8.io.in3 := io.in.bits.rs3Data
        vectorMemory8.io.funct3 := io.in.bits.funct3
        vectorMemory8.io.isLoad := io.in.bits.isLoad
        vectorMemory8.io.isStrided := io.in.bits.isStrided
        vectorMemory8.io.isIndexed := io.in.bits.isIndexed
        vectorMemory8.io.stride := io.in.bits.stride
        vectorMemory8.io.mask := io.mask
        result := vectorMemory8.io.out
        memReadReq := vectorMemory8.io.memRead
        memWriteReq := vectorMemory8.io.memWrite
      }
      is(1.U) { // 16-bit elements
        vectorMemory16.io.in1 := io.in.bits.rs1Data
        vectorMemory16.io.in2 := io.in.bits.rs2Data
        vectorMemory16.io.in3 := io.in.bits.rs3Data
        vectorMemory16.io.funct3 := io.in.bits.funct3
        vectorMemory16.io.isLoad := io.in.bits.isLoad
        vectorMemory16.io.isStrided := io.in.bits.isStrided
        vectorMemory16.io.isIndexed := io.in.bits.isIndexed
        vectorMemory16.io.stride := io.in.bits.stride
        vectorMemory16.io.mask := io.mask
        result := vectorMemory16.io.out
        memReadReq := vectorMemory16.io.memRead
        memWriteReq := vectorMemory16.io.memWrite
      }
      is(2.U) { // 32-bit elements
        vectorMemory32.io.in1 := io.in.bits.rs1Data
        vectorMemory32.io.in2 := io.in.bits.rs2Data
        vectorMemory32.io.in3 := io.in.bits.rs3Data
        vectorMemory32.io.funct3 := io.in.bits.funct3
        vectorMemory32.io.isLoad := io.in.bits.isLoad
        vectorMemory32.io.isStrided := io.in.bits.isStrided
        vectorMemory32.io.isIndexed := io.in.bits.isIndexed
        vectorMemory32.io.stride := io.in.bits.stride
        vectorMemory32.io.mask := io.mask
        result := vectorMemory32.io.out
        memReadReq := vectorMemory32.io.memRead
        memWriteReq := vectorMemory32.io.memWrite
      }
      is(3.U) { // 64-bit elements
        vectorMemory64.io.in1 := io.in.bits.rs1Data
        vectorMemory64.io.in2 := io.in.bits.rs2Data
        vectorMemory64.io.in3 := io.in.bits.rs3Data
        vectorMemory64.io.funct3 := io.in.bits.funct3
        vectorMemory64.io.isLoad := io.in.bits.isLoad
        vectorMemory64.io.isStrided := io.in.bits.isStrided
        vectorMemory64.io.isIndexed := io.in.bits.isIndexed
        vectorMemory64.io.stride := io.in.bits.stride
        vectorMemory64.io.mask := io.mask
        result := vectorMemory64.io.out
        memReadReq := vectorMemory64.io.memRead
        memWriteReq := vectorMemory64.io.memWrite
      }
    }

    outResultReg := result

    // Handle memory requests
    when(io.in.bits.isLoad) {
      memReadValidReg := memReadReq.valid
      memReadBundleReg := memReadReq.bits
    }.otherwise {
      memWriteValidReg := memWriteReq.valid
      memWriteBundleReg := memWriteReq.bits
    }
  }.otherwise {
    outValidReg := false.B
    memReadValidReg := false.B
    memWriteValidReg := false.B
  }

  // Connect output signals
  io.out.valid := outValidReg
  io.out.bits.result := outResultReg
  io.out.bits.warpID := outWarpIDReg
  io.out.bits.rd := outRdReg
  io.out.bits.exception := outExceptionReg
  io.out.bits.fflags := 0.U // Vector Memory does not set fflags

  // Connect memory interface
  io.memRead.valid := memReadValidReg
  io.memRead.bits := memReadBundleReg
  io.memWrite.valid := memWriteValidReg
  io.memWrite.bits := memWriteBundleReg
}

/** Vector Memory Module
  *
  * Performs vector memory access operations on elements of specified width
  */
class VectorMemory(parameter: VectorParameter, elementWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in1 = Input(Vec(parameter.threadNum, UInt(parameter.xLen.W))) // Base address
    val in2 = Input(Vec(parameter.threadNum, UInt(parameter.xLen.W))) // Offset or data
    val in3 = Input(Vec(parameter.threadNum, UInt(parameter.xLen.W))) // Additional data
    val funct3 = Input(UInt(3.W))
    val isLoad = Input(Bool())
    val isStrided = Input(Bool())
    val isIndexed = Input(Bool())
    val stride = Input(UInt(32.W))
    val mask = Input(Vec(parameter.threadNum, Bool()))
    val out = Output(Vec(parameter.threadNum, UInt(parameter.xLen.W)))
    val memRead = DecoupledIO(new VectorMemoryReadBundle(parameter))
    val memWrite = DecoupledIO(new VectorMemoryWriteBundle(parameter))
  })

  val elementsPerWord = parameter.xLen / elementWidth
  val result = Wire(Vec(parameter.threadNum, UInt(parameter.xLen.W)))

  // Calculate addresses for each thread
  for (thread <- 0 until parameter.threadNum) {
    val threadResult = Wire(Vec(elementsPerWord, UInt(elementWidth.W)))
    val threadAddr = Wire(Vec(elementsPerWord, UInt(parameter.xLen.W)))
    val threadData = Wire(Vec(elementsPerWord, UInt(elementWidth.W)))

    // Calculate addresses based on access pattern
    for (elem <- 0 until elementsPerWord) {
      val baseAddr = io.in1(thread)
      val offset = io.in2(thread)

      val addr = Wire(UInt(parameter.xLen.W))

      when(io.isStrided) {
        // Strided access: base + elem * stride
        addr := baseAddr + (elem.U * io.stride)
      }.elsewhen(io.isIndexed) {
        // Indexed access: base + offset[elem]
        val elemStart = elem * elementWidth
        val elemEnd = elemStart + elementWidth - 1
        val indexOffset = offset(elemEnd, elemStart)
        addr := baseAddr + indexOffset
      }.otherwise {
        // Unit stride access: base + elem * elementWidth
        addr := baseAddr + (elem.U * elementWidth.U)
      }

      threadAddr(elem) := addr

      // Extract data for stores
      when(!io.isLoad) {
        val elemStart = elem * elementWidth
        val elemEnd = elemStart + elementWidth - 1
        threadData(elem) := io.in2(thread)(elemEnd, elemStart)
      }
    }

    // Generate memory requests
    when(io.isLoad) {
      io.memRead.valid := true.B
      io.memRead.bits.addr := threadAddr
      io.memRead.bits.size := io.funct3
      io.memRead.bits.mask := io.mask
      io.memRead.bits.warpID := 0.U // TODO: Add warpID to interface
      io.memRead.bits.rd := 0.U // TODO: Add rd to interface

      // For now, return zeros (would be replaced by actual memory response)
      threadResult.foreach(_ := 0.U)
    }.otherwise {
      io.memWrite.valid := true.B
      io.memWrite.bits.addr := threadAddr
      io.memWrite.bits.data := threadData.asUInt
      io.memWrite.bits.size := io.funct3
      io.memWrite.bits.mask := io.mask
      io.memWrite.bits.warpID := 0.U // TODO: Add warpID to interface

      // For stores, return the data being written
      threadResult := threadData
    }

    // Combine elements into result word
    result(thread) := threadResult.asUInt
  }

  io.out := result
}
