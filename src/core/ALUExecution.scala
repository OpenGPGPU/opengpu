package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.SerializableModule
import org.chipsalliance.rocketv.{ALU, ALUParameter}

class ALUExecutionInterface(parameter: OGPUDecoderParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())

  // Input from Issue stage
  val in = Flipped(DecoupledIO(new Bundle {
    val warpID = UInt(log2Ceil(parameter.warpNum).W)
    val execType = UInt(2.W)
    val funct3 = UInt(3.W)
    val funct7 = UInt(7.W)
    val pc = UInt(parameter.xLen.W)
    val rs1Data = UInt(parameter.xLen.W)
    val rs2Data = UInt(parameter.xLen.W)
    val rd = UInt(5.W)
    val isRVC = Bool()
  }))

  // Execution results output
  val out = DecoupledIO(new Bundle {
    val result = UInt(parameter.xLen.W)
    val warpID = UInt(log2Ceil(parameter.warpNum).W)
    val rd = UInt(5.W)
    val exception = Bool()
  })
}

@instantiable
class ALUExecution(val parameter: OGPUDecoderParameter)
    extends FixedIORawModule(new ALUExecutionInterface(parameter))
    with SerializableModule[OGPUDecoderParameter]
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
  }.otherwise {
    outValidReg := false.B
  }

  // Connect output signals
  io.out.valid := outValidReg
  io.out.bits.result := outResultReg
  io.out.bits.warpID := outWarpIDReg
  io.out.bits.rd := outRdReg
  io.out.bits.exception := outExceptionReg
}
