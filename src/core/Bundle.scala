/** Core Bundle Definitions for OGPU
  *
  * This package contains the main bundle definitions used throughout the OGPU core, including task bundles, stack data
  * structures, and commit data structures.
  */
package ogpu.core

import chisel3._
import chisel3.util._

/** Compute Unit Task Bundle
  *
  * Represents a task to be executed by a compute unit, containing all necessary state information for execution.
  *
  * @param threadNum
  *   Number of threads in the warp
  * @param warpNum
  *   Number of warps
  * @param dimNum
  *   Number of dimensions in vector operations
  * @param xLen
  *   Scalar register width in bits
  * @param dimWidth
  *   Vector dimension width in bits (default 16)
  */
class CuTaskBundle(
  threadNum: Int, // Number of threads in the warp
  warpNum:   Int, // Number of warps
  dimNum:    Int, // Number of dimensions in vector operations
  xLen:      Int, // Scalar register width in bits
  dimWidth:  Int = 16) // Vector dimension width in bits
    extends Bundle {
  val mask = Vec(threadNum, Bool()) // Thread active mask
  val pc = UInt(32.W) // Program counter value
  val vgprs = Vec(dimNum, UInt(dimWidth.W)) // Vector general purpose registers
  val vgpr_num = UInt(2.W) // Number of active VGPRs
  val sgprs = Vec(16, UInt(xLen.W)) // Scalar general purpose registers
  val sgpr_num = UInt(4.W) // Number of active SGPRs
}

/** Stack Data Structure
  *
  * Represents the state of a thread stack during divergence, including masks and program counter information.
  *
  * @param threadNum
  *   Number of threads in the warp
  * @param addrBits
  *   Number of bits for address representation
  */
class StackData(
  threadNum: Int, // Number of threads in the warp
  addrBits:  Int) // Address width in bits
    extends Bundle {
  val mask = Vec(threadNum, Bool()) // Current thread divergence mask
  val pc = UInt(addrBits.W) // Program counter at divergence point
  val orig_mask = Vec(threadNum, Bool()) // Original mask before divergence
}

/** Commit Scalar Data Structure
  *
  * Contains the data needed for instruction commit, including register write information and program counter state.
  *
  * @param xLen
  *   Scalar register width in bits
  * @param addrWidth
  *   Address width in bits
  * @param warpNum
  *   Number of warps
  * @param regNum
  *   Number of registers
  */
class CommitSData(
  xLen:     Int, // Scalar register width in bits
  addrBits: Int, // Address width in bits
  warpNum:  Int, // Number of warps
  regNum:   Int) // Number of registers
    extends Bundle {
  val regIDWidth = log2Ceil(regNum) // Width of register ID field

  val wid = UInt(log2Ceil(warpNum).W) // Warp ID
  val mask = Bool() // Commit mask
  val pc = UInt(addrBits.W) // Program counter value
  val rd = UInt(regIDWidth.W) // Destination register ID
  val data = UInt(xLen.W) // Data to be written
}

/** Commit Vector Data Structure
  *
  * Contains the data needed for instruction commit, including register write information and program counter state.
  *
  * @param xLen
  *   Scalar register width in bits
  * @param addrBits
  *   Address width in bits
  * @param warpNum
  *   Number of warps
  * @param regNum
  *   Number of registers
  * @param threadNum
  *   Number of threads
  */
class CommitVData(
  xLen:      Int, // register width in bits
  threadNum: Int, // Number of thread in a warp
  addrBits:  Int, // Address width in bits
  warpNum:   Int, // Number of warps
  regNum:    Int) // Number of registers
    extends Bundle {
  val regIDWidth = log2Ceil(regNum) // Width of register ID field

  val wid = UInt(log2Ceil(warpNum).W) // Warp ID
  val mask = Bool() // Commit mask
  val pc = UInt(addrBits.W) // Program counter value
  val rd = UInt(regIDWidth.W) // Destination register ID
  val data = Vec(threadNum, UInt(xLen.W)) // Data to be written
}
