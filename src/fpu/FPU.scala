package ogpu.fpu

import chisel3._
import chisel3.experimental._
import chisel3.util._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.SerializableModule
import ogpu.core._

class FpnewTopIO(parameter: OGPUDecoderParameter) extends Bundle {
  val clk_i = Input(Clock())
  val rst_ni = Input(Bool())

  // Input signals
  val operands_i_flat = Input(UInt((parameter.xLen * 3).W))
  val rnd_mode_i = Input(UInt(3.W))
  val op_i = Input(UInt(5.W))
  val op_mod_i = Input(Bool())
  val src_fmt_i = Input(UInt(2.W))
  val dst_fmt_i = Input(UInt(2.W))
  val int_fmt_i = Input(UInt(2.W))
  val vectorial_op_i = Input(Bool())
  val tag_i = Input(UInt(5.W))

  // Input Handshake
  val in_valid_i = Input(Bool())
  val in_ready_o = Output(Bool())
  val flush_i = Input(Bool())

  // Output signals
  val result_o = Output(UInt(parameter.xLen.W))
  val status_o = Output(UInt(5.W))
  val tag_o = Output(UInt(5.W))

  // Output handshake
  val out_valid_o = Output(Bool())
  val out_ready_i = Input(Bool())

  // Indication of valid data in flight
  val busy_o = Output(Bool())
}

class fpnew_wrapper(
  val parameter:   OGPUDecoderParameter,
  val numOperands: Int = 3)
    extends BlackBox(
      Map(
        "WIDTH" -> 32,
        "NUM_OPERANDS" -> numOperands
      )
    )
    with HasBlackBoxResource
    with HasBlackBoxPath {
  val io = IO(new FpnewTopIO(parameter))
  override def desiredName = "fpnew_wrapper"
  addPath("./src/fpu/combined.sv")
}

class FPU(val parameter: OGPUDecoderParameter) extends Module {
  val numOperands = 3

  val io = IO(new Bundle {
    val clk_i = Input(Clock())
    val rst_ni = Input(Bool())
    val op_a = Input(UInt(parameter.xLen.W))
    val op_b = Input(UInt(parameter.xLen.W))
    val op_c = Input(UInt(parameter.xLen.W))
    val rnd_mode = Input(UInt(3.W))
    val op = Input(UInt(5.W))
    val op_mod = Input(Bool())
    val src_fmt = Input(UInt(2.W))
    val dst_fmt = Input(UInt(2.W))
    val int_fmt = Input(UInt(2.W))
    val vectorial_op = Input(Bool())
    val tag_i = Input(UInt(5.W))

    val in_valid = Input(Bool())
    val in_ready = Output(Bool())
    val flush = Input(Bool())

    val result = Output(UInt(parameter.xLen.W))
    val status = Output(UInt(5.W))
    val tag_o = Output(UInt(5.W))

    val out_valid = Output(Bool())
    val out_ready = Input(Bool())
    val busy = Output(Bool())
  })

  val fpnew = Module(new fpnew_wrapper(parameter, 3))

  fpnew.io.clk_i := io.clk_i
  fpnew.io.rst_ni := io.rst_ni
  fpnew.io.operands_i_flat := io.op_c ## io.op_b ## io.op_a // Concatenate operands in the order expected by fpnew
  fpnew.io.rnd_mode_i := io.rnd_mode
  fpnew.io.op_i := io.op
  fpnew.io.op_mod_i := io.op_mod
  fpnew.io.src_fmt_i := io.src_fmt
  fpnew.io.dst_fmt_i := io.dst_fmt
  fpnew.io.int_fmt_i := io.int_fmt
  fpnew.io.vectorial_op_i := io.vectorial_op
  fpnew.io.tag_i := io.tag_i
  fpnew.io.in_valid_i := io.in_valid
  io.in_ready := fpnew.io.in_ready_o
  fpnew.io.flush_i := io.flush

  io.result := fpnew.io.result_o
  io.status := fpnew.io.status_o
  io.tag_o := fpnew.io.tag_o

  io.out_valid := fpnew.io.out_valid_o
  fpnew.io.out_ready_i := io.out_ready

  io.busy := fpnew.io.busy_o
}

class FPUExecutionInterface(parameter: OGPUDecoderParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())

  val in = Flipped(DecoupledIO(new Bundle {
    val warpID = UInt(log2Ceil(parameter.warpNum).W)
    val execType = UInt(2.W)
    val funct3 = UInt(3.W)
    val funct7 = UInt(7.W)
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
