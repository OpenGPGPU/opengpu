package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.SerializableModule
import org.chipsalliance.rocketv.{ALU, ALUParameter}

class ALUExecutionInterface(parameter: OGPUParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())

  // Input from Issue stage
  val in = Flipped(DecoupledIO(new ALUOperandBundle(parameter)))

  // Execution results output
  val out = DecoupledIO(new ResultBundle(parameter))

  // Branch info output
  val branchInfo = DecoupledIO(new BranchInfoBundle(parameter))
}

@instantiable
class ALUExecution(val parameter: OGPUParameter)
    extends FixedIORawModule(new ALUExecutionInterface(parameter))
    with SerializableModule[OGPUParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // ALU instance
  val alu = Module(new ALU(new ALUParameter(parameter.xLen)))

  // Pipeline control
  io.in.ready := true.B // Always ready to accept new instruction

  // Output registers
  val outValidReg = RegInit(false.B)
  val outResultReg = RegInit(0.U(parameter.xLen.W))
  val outWarpIDReg = RegInit(0.U(log2Ceil(parameter.warpNum).W))
  val outRdReg = RegInit(0.U(5.W))
  val outExceptionReg = RegInit(false.B)

  // Output registers for branch info
  val branchValidReg = RegInit(false.B)
  val branchInfoReg = Reg(new BranchInfoBundle(parameter))

  // ALU execution
  alu.io.dw := false.B
  alu.io.fn := Cat(io.in.bits.funct7(5), io.in.bits.funct3)
  alu.io.in1 := io.in.bits.rs1Data
  alu.io.in2 := io.in.bits.rs2Data

  // Register output results
  when(io.in.valid) {
    outValidReg := true.B
    outResultReg := alu.io.out
    outWarpIDReg := io.in.bits.warpID
    outRdReg := io.in.bits.rd
    outExceptionReg := false.B
    branchValidReg := true.B
    branchInfoReg.cmp_out := alu.io.cmp_out
    branchInfoReg.warpID := io.in.bits.warpID
    branchInfoReg.pc := io.in.bits.pc
    branchInfoReg.imm := io.in.bits.imm
    branchInfoReg.rs1Data := io.in.bits.rs1Data
    branchInfoReg.isRVC := io.in.bits.isRVC
    branchInfoReg.branch.isJal := io.in.bits.branch.isJal
    branchInfoReg.branch.isJalr := io.in.bits.branch.isJalr
    branchInfoReg.branch.isBranch := io.in.bits.branch.isBranch
  }.otherwise {
    outValidReg := false.B
    branchValidReg := false.B
  }

  // Connect output signals
  io.out.valid := outValidReg
  io.out.bits.result := outResultReg
  io.out.bits.warpID := outWarpIDReg
  io.out.bits.rd := outRdReg
  io.out.bits.exception := outExceptionReg
  io.out.bits.fflags := 0.U // ALU does not set fflags

  // Connect branchInfo output
  io.branchInfo.valid := branchValidReg
  io.branchInfo.bits := branchInfoReg
}
