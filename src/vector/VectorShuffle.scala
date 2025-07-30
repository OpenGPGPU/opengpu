// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2024 OpenGPU Contributors

package ogpu.vector

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.SerializableModule

/** Vector Shuffle Execution Interface
  *
  * Defines the interface for vector shuffle operations
  */
class VectorShuffleExecutionInterface(parameter: VectorParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())

  // Input from Issue stage
  val in = Flipped(DecoupledIO(new VectorShuffleOperandBundle(parameter)))

  // Execution results output
  val out = DecoupledIO(new VectorResultBundle(parameter))

  // Vector mask for conditional execution
  val mask = Input(Vec(parameter.threadNum, Bool()))
}

/** Vector Shuffle Operand Bundle
  *
  * Contains operands for vector shuffle operations
  */
class VectorShuffleOperandBundle(parameter: VectorParameter) extends Bundle {
  val warpID = UInt(log2Ceil(parameter.warpNum).W)
  val pc = UInt(32.W)
  val rd = UInt(5.W)
  val rs1Data = Vec(parameter.threadNum, UInt(parameter.xLen.W)) // Source data
  val rs2Data = Vec(parameter.threadNum, UInt(parameter.xLen.W)) // Index or control
  val rs3Data = Vec(parameter.threadNum, UInt(parameter.xLen.W)) // Additional data
  val imm = UInt(32.W)
  val funct3 = UInt(3.W)
  val funct7 = UInt(7.W)
  val isRVC = Bool()
  val vectorOp = UInt(4.W) // Vector operation type
  val vectorWidth = UInt(2.W) // Vector element width (8, 16, 32, 64)
  val vectorLength = UInt(8.W) // Vector length
  val shuffleType = UInt(
    3.W
  ) // 0=permute, 1=shuffle, 2=interleave, 3=deinterleave, 4=rotate, 5=reverse, 6=duplicate, 7=extract
  val shuffleMode = UInt(2.W) // 0=within thread, 1=across threads, 2=within warp, 3=across warps
}

/** Vector Shuffle Execution Module
  *
  * Handles vector shuffle operations
  */
@instantiable
class VectorShuffleExecution(val parameter: VectorParameter)
    extends FixedIORawModule(new VectorShuffleExecutionInterface(parameter))
    with SerializableModule[VectorParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // Vector Shuffle instances for different element widths
  val vectorShuffle8 = Module(new VectorShuffle(parameter, 8))
  val vectorShuffle16 = Module(new VectorShuffle(parameter, 16))
  val vectorShuffle32 = Module(new VectorShuffle(parameter, 32))
  val vectorShuffle64 = Module(new VectorShuffle(parameter, 64))

  // Pipeline control
  io.in.ready := true.B // Always ready to accept new instruction

  // Output registers
  val outValidReg = RegInit(false.B)
  val outResultReg = RegInit(Vec(parameter.threadNum, 0.U(parameter.xLen.W)))
  val outWarpIDReg = RegInit(0.U(log2Ceil(parameter.warpNum).W))
  val outRdReg = RegInit(0.U(5.W))
  val outExceptionReg = RegInit(false.B)

  // Vector Shuffle operation execution
  when(io.in.valid) {
    outValidReg := true.B
    outWarpIDReg := io.in.bits.warpID
    outRdReg := io.in.bits.rd
    outExceptionReg := false.B

    // Select appropriate Shuffle unit based on vector width
    val result = Wire(Vec(parameter.threadNum, UInt(parameter.xLen.W)))

    switch(io.in.bits.vectorWidth) {
      is(0.U) { // 8-bit elements
        vectorShuffle8.io.in1 := io.in.bits.rs1Data
        vectorShuffle8.io.in2 := io.in.bits.rs2Data
        vectorShuffle8.io.in3 := io.in.bits.rs3Data
        vectorShuffle8.io.shuffleType := io.in.bits.shuffleType
        vectorShuffle8.io.shuffleMode := io.in.bits.shuffleMode
        vectorShuffle8.io.mask := io.mask
        result := vectorShuffle8.io.out
      }
      is(1.U) { // 16-bit elements
        vectorShuffle16.io.in1 := io.in.bits.rs1Data
        vectorShuffle16.io.in2 := io.in.bits.rs2Data
        vectorShuffle16.io.in3 := io.in.bits.rs3Data
        vectorShuffle16.io.shuffleType := io.in.bits.shuffleType
        vectorShuffle16.io.shuffleMode := io.in.bits.shuffleMode
        vectorShuffle16.io.mask := io.mask
        result := vectorShuffle16.io.out
      }
      is(2.U) { // 32-bit elements
        vectorShuffle32.io.in1 := io.in.bits.rs1Data
        vectorShuffle32.io.in2 := io.in.bits.rs2Data
        vectorShuffle32.io.in3 := io.in.bits.rs3Data
        vectorShuffle32.io.shuffleType := io.in.bits.shuffleType
        vectorShuffle32.io.shuffleMode := io.in.bits.shuffleMode
        vectorShuffle32.io.mask := io.mask
        result := vectorShuffle32.io.out
      }
      is(3.U) { // 64-bit elements
        vectorShuffle64.io.in1 := io.in.bits.rs1Data
        vectorShuffle64.io.in2 := io.in.bits.rs2Data
        vectorShuffle64.io.in3 := io.in.bits.rs3Data
        vectorShuffle64.io.shuffleType := io.in.bits.shuffleType
        vectorShuffle64.io.shuffleMode := io.in.bits.shuffleMode
        vectorShuffle64.io.mask := io.mask
        result := vectorShuffle64.io.out
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
  io.out.bits.fflags := 0.U // Vector Shuffle does not set fflags
}

/** Vector Shuffle Module
  *
  * Performs vector shuffle operations on elements of specified width
  */
class VectorShuffle(parameter: VectorParameter, elementWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in1 = Input(Vec(parameter.threadNum, UInt(parameter.xLen.W))) // Source data
    val in2 = Input(Vec(parameter.threadNum, UInt(parameter.xLen.W))) // Index or control
    val in3 = Input(Vec(parameter.threadNum, UInt(parameter.xLen.W))) // Additional data
    val shuffleType = Input(UInt(3.W))
    val shuffleMode = Input(UInt(2.W))
    val mask = Input(Vec(parameter.threadNum, Bool()))
    val out = Output(Vec(parameter.threadNum, UInt(parameter.xLen.W)))
  })

  val elementsPerWord = parameter.xLen / elementWidth
  val result = Wire(Vec(parameter.threadNum, UInt(parameter.xLen.W)))

  // Process each thread
  for (thread <- 0 until parameter.threadNum) {
    val threadResult = Wire(Vec(elementsPerWord, UInt(elementWidth.W)))

    // Extract elements from input data
    val sourceElements = Wire(Vec(elementsPerWord, UInt(elementWidth.W)))
    val indexElements = Wire(Vec(elementsPerWord, UInt(elementWidth.W)))
    val additionalElements = Wire(Vec(elementsPerWord, UInt(elementWidth.W)))

    for (elem <- 0 until elementsPerWord) {
      val elemStart = elem * elementWidth
      val elemEnd = elemStart + elementWidth - 1
      sourceElements(elem) := io.in1(thread)(elemEnd, elemStart)
      indexElements(elem) := io.in2(thread)(elemEnd, elemStart)
      additionalElements(elem) := io.in3(thread)(elemEnd, elemStart)
    }

    // Process each element in the word
    for (elem <- 0 until elementsPerWord) {
      // Vector shuffle operation based on shuffleType
      val elemResult = Wire(UInt(elementWidth.W))

      when(io.mask(thread)) {
        switch(io.shuffleType) {
          is(0.U) { // Permute - rearrange elements based on index
            val index = indexElements(elem)(log2Ceil(elementsPerWord) - 1, 0)
            val permutedData = sourceElements(index)
            elemResult := permutedData
          }
          is(1.U) { // Shuffle - random shuffle based on control bits
            val shuffleControl = indexElements(elem)(log2Ceil(elementsPerWord) - 1, 0)
            val shuffledData = sourceElements(shuffleControl)
            elemResult := shuffledData
          }
          is(2.U) { // Interleave - interleave elements from two sources
            when(elem.U % 2.U === 0.U) {
              elemResult := sourceElements(elem)
            }.otherwise {
              elemResult := additionalElements(elem)
            }
          }
          is(3.U) { // Deinterleave - separate interleaved elements
            val deinterleaveIndex = elem.U / 2.U
            when(elem.U % 2.U === 0.U) {
              elemResult := sourceElements(deinterleaveIndex)
            }.otherwise {
              elemResult := indexElements(deinterleaveIndex)
            }
          }
          is(4.U) { // Rotate - rotate elements by specified amount
            val rotateAmount = indexElements(elem)(log2Ceil(elementsPerWord) - 1, 0)
            val rotatedIndex = (elem.U + rotateAmount) % elementsPerWord.U
            val rotatedData = sourceElements(rotatedIndex)
            elemResult := rotatedData
          }
          is(5.U) { // Reverse - reverse the order of elements
            val reversedIndex = (elementsPerWord - 1 - elem).U
            val reversedData = sourceElements(reversedIndex)
            elemResult := reversedData
          }
          is(6.U) { // Duplicate - duplicate a single element across all positions
            val duplicateIndex = indexElements(elem)(log2Ceil(elementsPerWord) - 1, 0)
            val duplicatedData = sourceElements(duplicateIndex)
            elemResult := duplicatedData
          }
          is(7.U) { // Extract - extract elements at specified positions
            val extractIndex = indexElements(elem)(log2Ceil(elementsPerWord) - 1, 0)
            val extractedData = sourceElements(extractIndex)
            elemResult := extractedData
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
