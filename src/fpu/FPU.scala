package ogpu.fpu

import chisel3._
import chisel3.experimental._
import chisel3.util._
import ogpu.core._

class FpnewTopBundle(parameter: OGPUParameter) extends Bundle {
  val operands_i_flat = UInt((parameter.xLen * 3).W)
  val rnd_mode_i = UInt(3.W)
  val op_i = UInt(5.W)
  val op_mod_i = Bool()
  val src_fmt_i = UInt(2.W)
  val dst_fmt_i = UInt(2.W)
  val int_fmt_i = UInt(2.W)
  val vectorial_op_i = Bool()
  val tag_i = Bool()
  val in_valid_i = Bool()
  val in_ready_o = Bool()
  val flush_i = Bool()
  val result_o = UInt(parameter.xLen.W)
  val status_o = UInt(5.W)
  val tag_o = Bool()
  val out_valid_o = Bool()
  val out_ready_i = Bool()
  val busy_o = Bool()
}

class FpnewTopIO(parameter: OGPUParameter) extends Bundle {
  val clk_i = Input(Clock())
  val rst_ni = Input(Bool())
  val operands_i_flat = Input(UInt((parameter.xLen * 3).W))
  val rnd_mode_i = Input(UInt(3.W))
  val op_i = Input(UInt(5.W))
  val op_mod_i = Input(Bool())
  val src_fmt_i = Input(UInt(2.W))
  val dst_fmt_i = Input(UInt(2.W))
  val int_fmt_i = Input(UInt(2.W))
  val vectorial_op_i = Input(Bool())
  val tag_i = Input(Bool())
  val in_valid_i = Input(Bool())
  val in_ready_o = Output(Bool())
  val flush_i = Input(Bool())
  val result_o = Output(UInt(parameter.xLen.W))
  val status_o = Output(UInt(5.W))
  val tag_o = Output(Bool())
  val out_valid_o = Output(Bool())
  val out_ready_i = Input(Bool())
  val busy_o = Output(Bool())
}

class fpnew_wrapper(
  val parameter:   OGPUParameter,
  val numOperands: Int = 3)
    extends BlackBox(
      Map(
        "WIDTH" -> parameter.xLen,
        "NUM_OPERANDS" -> numOperands
      )
    )
    with HasBlackBoxResource
    with HasBlackBoxPath {
  val io = IO(new FpnewTopIO(parameter))
  override def desiredName = "fpnew_wrapper"
  addPath("./out/fpuVerilogGen/compile.dest/combined.sv")
}

class FPUBundle(parameter: OGPUParameter) extends Bundle {
  val op_a = UInt(parameter.xLen.W)
  val op_b = UInt(parameter.xLen.W)
  val op_c = UInt(parameter.xLen.W)
  val rnd_mode = UInt(3.W)
  val op = UInt(5.W)
  val op_mod = Bool()
  val src_fmt = UInt(2.W)
  val dst_fmt = UInt(2.W)
  val int_fmt = UInt(2.W)
  val vectorial_op = Bool()
  val tag_i = UInt(5.W)
  val in_valid = Bool()
  val in_ready = Bool()
  val flush = Bool()
  val result = UInt(parameter.xLen.W)
  val status = UInt(5.W)
  val tag_o = UInt(5.W)
  val out_valid = Bool()
  val out_ready = Bool()
  val busy = Bool()
}

class FPUInterface(parameter: OGPUParameter) extends Bundle {
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
}

class FPU(val parameter: OGPUParameter) extends Module {
  val numOperands = 3

  val io = IO(new FPUInterface(parameter))

  val fpnew = Module(new fpnew_wrapper(parameter, 3))

  fpnew.io.clk_i := io.clk_i
  fpnew.io.rst_ni := io.rst_ni
  // 拼接顺序与 wrapper 保持一致
  fpnew.io.operands_i_flat := io.op_c ## io.op_b ## io.op_a
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
