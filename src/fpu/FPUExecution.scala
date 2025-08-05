package ogpu.fpu

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.SerializableModule

import ogpu.core._

class FPUExecutionBundle(parameter: OGPUParameter) extends Bundle {
  val in = Flipped(DecoupledIO(new FPUOperandBundle(parameter)))
  val out = DecoupledIO(new ResultBundle(parameter))
}

class FPUExecutionInterface(parameter: OGPUParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())

  val in = Flipped(DecoupledIO(new FPUOperandBundle(parameter)))
  val out = DecoupledIO(new ResultBundle(parameter))
}

@instantiable
class FPUExecution(val parameter: OGPUParameter)
    extends FixedIORawModule(new FPUExecutionInterface(parameter))
    with SerializableModule[OGPUParameter]
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
  fpu.io.rnd_mode := io.in.bits.rnd_mode
  fpu.io.op := io.in.bits.op
  fpu.io.op_mod := io.in.bits.op_mod
  fpu.io.src_fmt := io.in.bits.src_fmt
  fpu.io.dst_fmt := io.in.bits.dst_fmt
  fpu.io.int_fmt := io.in.bits.int_fmt
  fpu.io.vectorial_op := io.in.bits.vectorial_op
  fpu.io.tag_i := io.in.bits.tag_i
  fpu.io.in_valid := io.in.valid
  fpu.io.out_ready := true.B
  fpu.io.flush := io.in.bits.flush

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
