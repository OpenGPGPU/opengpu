package ogpu.fpu

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.SerializableModule

import ogpu.core._

class FPUExecutionInterface(parameter: OGPUDecoderParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())

  val in = Flipped(DecoupledIO(new Bundle {
    val warpID = UInt(log2Ceil(parameter.warpNum).W)
    val execType = UInt(2.W)
    val operation = UInt(4.W)
    val operation_i = Bool()
    val pc = UInt(parameter.xLen.W)
    val rs1Data = UInt(parameter.xLen.W)
    val rs2Data = UInt(parameter.xLen.W)
    val rs3Data = UInt(parameter.xLen.W)
    val rd = UInt(5.W)
    val isRVC = Bool()
  }))

  val out = DecoupledIO(new Bundle {
    val result = UInt(parameter.xLen.W)
    val warpID = UInt(log2Ceil(parameter.warpNum).W)
    val rd = UInt(5.W)
    val exception = Bool()
    val fflags = UInt(5.W)
  })
}

@instantiable
class FPUExecution(val parameter: OGPUDecoderParameter)
    extends FixedIORawModule(new FPUExecutionInterface(parameter))
    with SerializableModule[OGPUDecoderParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  val fpu = Module(new FPU(parameter))

  io.in.ready := true.B

  val outValidReg = RegInit(false.B)
  val outResultReg = RegInit(0.U(parameter.xLen.W))
  val outWarpIDReg = RegInit(0.U(log2Ceil(parameter.warpNum).W))
  val outRdReg = RegInit(0.U(5.W))
  val outExceptionReg = RegInit(false.B)
  val outFflagsReg = RegInit(0.U(5.W))

  fpu.io.clk_i := io.clock
  fpu.io.rst_ni := !io.reset
  fpu.io.op_a := io.in.bits.rs1Data
  fpu.io.op_b := io.in.bits.rs2Data
  fpu.io.op_c := io.in.bits.rs3Data
  fpu.io.rnd_mode := 0.U
  fpu.io.op := 0.U
  fpu.io.op_mod := false.B
  fpu.io.src_fmt := 0.U
  fpu.io.dst_fmt := 0.U
  fpu.io.int_fmt := 0.U
  fpu.io.vectorial_op := false.B
  fpu.io.tag_i := 0.U
  fpu.io.in_valid := io.in.valid
  fpu.io.out_ready := true.B
  fpu.io.flush := false.B

  when(io.in.valid && fpu.io.out_valid) {
    outValidReg := true.B
    outResultReg := fpu.io.result
    outWarpIDReg := io.in.bits.warpID
    outRdReg := io.in.bits.rd
    outExceptionReg := fpu.io.status.orR
    outFflagsReg := fpu.io.status
  }.otherwise {
    outValidReg := false.B
  }

  io.out.valid := outValidReg
  io.out.bits.result := outResultReg
  io.out.bits.warpID := outWarpIDReg
  io.out.bits.rd := outRdReg
  io.out.bits.exception := outExceptionReg
  io.out.bits.fflags := outFflagsReg
}
