package ogpu.fpu

import chisel3._
import chisel3.experimental._
import chisel3.util._

// 定义 fpnew_pkg 类型，实际值可能需要根据 fpnew_pkg.sv 进行调整
object FpnewPkg {
  // fpnew_pkg::roundmode_e (通常3位)
  def roundmodeWidth = 3.W
  // fpnew_pkg::operation_e (通常5位)
  def operationWidth = 5.W
  // fpnew_pkg::fp_format_e (通常2位)
  def fpFormatWidth = 2.W
  // fpnew_pkg::int_format_e (通常2位，假设)
  def intFormatWidth = 2.W
  // fpnew_pkg::status_t (通常5位)
  def statusWidth = 5.W
  // TagType (假设5位，与 FPU.scala 中原定义一致)
  def tagWidth = 5.W
}

class FpnewTopIO(val dataWidth: Int, val numOperands: Int) extends Bundle {
  val clk_i = Input(Clock())
  val rst_ni = Input(Bool()) // rst_ni 通常是低电平有效复位，所以命名为 rst_ni (reset not)

  // Input signals
  val operands_i = Input(Vec(numOperands, UInt(dataWidth.W)))
  val rnd_mode_i = Input(UInt(FpnewPkg.roundmodeWidth))
  val op_i = Input(UInt(FpnewPkg.operationWidth))
  val op_mod_i = Input(Bool())
  val src_fmt_i = Input(UInt(FpnewPkg.fpFormatWidth))
  val dst_fmt_i = Input(UInt(FpnewPkg.fpFormatWidth))
  val int_fmt_i = Input(UInt(FpnewPkg.intFormatWidth))
  val vectorial_op_i = Input(Bool())
  val tag_i = Input(UInt(FpnewPkg.tagWidth))

  // Input Handshake
  val in_valid_i = Input(Bool())
  val in_ready_o = Output(Bool())
  val flush_i = Input(Bool())

  // Output signals
  val result_o = Output(UInt(dataWidth.W))
  val status_o = Output(UInt(FpnewPkg.statusWidth))
  val tag_o = Output(UInt(FpnewPkg.tagWidth))

  // Output handshake
  val out_valid_o = Output(Bool())
  val out_ready_i = Input(Bool())

  // Indication of valid data in flight
  val busy_o = Output(Bool())
}

class FpnewTop(
  val expWidth:    Int = 8,
  val sigWidth:    Int = 24,
  val numOperands: Int = 3 // 对应 fpnew_top.sv 中的 NUM_OPERANDS
) extends BlackBox(
      Map(
        // "Features" 和 "Implementation" 是复杂的 SystemVerilog 结构体，
        // 在 ExtModule 中直接传递比较困难。这里假设 fpnew_top.sv 会使用默认值，
        // 或者您会修改 fpnew_top.sv 以接受更简单的参数。
        // "TagType" 也是一个类型参数，这里我们假设它在 Verilog 内部处理，
        // 或者通过其他方式配置。
        "WIDTH" -> (expWidth + sigWidth),
        "NUM_OPERANDS" -> numOperands
        // 如果 fpnew_top.sv 的参数 Features 和 Implementation 可以被具体数值替代，
        // 例如 Features_Width, Features_EnableNanBox 等，可以在这里添加它们。
        // 例如： "Features_Width" -> (expWidth + sigWidth)
        //       "Features_EnableNanBox" -> 1 // 假设使能
      )
    )
    with HasBlackBoxResource
    with HasBlackBoxPath {
  val io = IO(new FpnewTopIO(expWidth + sigWidth, numOperands))
  // Verilog模块名需要与SystemVerilog文件中的模块名完全一致
  override def desiredName = "fpnew_top"

  addPath("./depends/fpnew/src/fpnew_top.sv")
}

class FPU(val expWidth: Int = 8, val sigWidth: Int = 24) extends Module {
  // FPU 模块通常有3个操作数 op_a, op_b, op_c，对应 NUM_OPERANDS = 3
  val numOperands = 3
  val dataWidth = expWidth + sigWidth

  // FPU 的 IO 定义，这里我们保留与旧 FPU.scala 相似的接口，
  // 并在内部进行转换以匹配 FpnewTopIO
  val io = IO(new Bundle {
    val clk_i = Input(Clock())
    val rst_ni = Input(Bool())
    val op_a = Input(UInt(dataWidth.W))
    val op_b = Input(UInt(dataWidth.W))
    val op_c = Input(UInt(dataWidth.W)) // 如果 fpnew_top 始终需要3个操作数
    val rnd_mode = Input(UInt(FpnewPkg.roundmodeWidth))
    val op = Input(UInt(FpnewPkg.operationWidth))
    val op_mod = Input(Bool()) // 新增
    val src_fmt = Input(UInt(FpnewPkg.fpFormatWidth))
    val dst_fmt = Input(UInt(FpnewPkg.fpFormatWidth))
    val int_fmt = Input(UInt(FpnewPkg.intFormatWidth)) // 新增
    val vectorial_op = Input(Bool())
    val tag_i = Input(UInt(FpnewPkg.tagWidth))

    val in_valid = Input(Bool()) // 新增
    val in_ready = Output(Bool()) // 新增
    val flush = Input(Bool()) // 新增

    val result = Output(UInt(dataWidth.W))
    val status = Output(UInt(FpnewPkg.statusWidth))
    val tag_o = Output(UInt(FpnewPkg.tagWidth))

    val out_valid = Output(Bool()) // 新增
    val out_ready = Input(Bool()) // 新增
    val busy = Output(Bool())
  })

  val fpnew = Module(new FpnewTop(expWidth, sigWidth, numOperands))

  fpnew.io.clk_i := io.clk_i
  fpnew.io.rst_ni := io.rst_ni

  // 将独立的 op_a, op_b, op_c 连接到 operands_i Vec
  // 注意：fpnew_top.sv 中的 operands_i 是一个数组。Chisel 中用 Vec 表示。
  // operands_i[0] 对应 op_a, operands_i[1] 对应 op_b, etc.
  fpnew.io.operands_i := VecInit(io.op_a, io.op_b, io.op_c)

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
