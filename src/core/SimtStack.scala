package ogpu.core

import chisel3._
import chisel3.experimental.hierarchy.{instantiable, public, Instance, Instantiate}
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}
import chisel3.properties.{AnyClassType, Class, ClassType, Property}
import chisel3.util._
import chisel3.util.experimental.BitSet
import org.chipsalliance.rocketv.RegFile

object SimtStackParameter {
  implicit def bitSetP: upickle.default.ReadWriter[BitSet] = upickle.default
    .readwriter[String]
    .bimap[BitSet](
      bs => bs.terms.map("b" + _.rawString).mkString("\n"),
      str => if (str.isEmpty) BitSet.empty else BitSet.fromString(str)
    )

  implicit def rwP: upickle.default.ReadWriter[SimtStackParameter] = upickle.default.macroRW[SimtStackParameter]
}

case class SimtStackParameter(
  useAsyncReset: Boolean,
  clockGate:     Boolean,
  threadNum:     Int,
  paddrBits:     Int,
  stackDepth:    Int)
    extends SerializableModuleParameter {
  def resetVectorBits: Int = paddrBits
}

class SimtStackInterface(parameter: SimtStackParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(if (parameter.useAsyncReset) AsyncReset() else Bool())
  val resetVector = Input(Const(UInt(parameter.resetVectorBits.W)))
  val stack = new StackData(parameter.threadNum, parameter.paddrBits)
}

@instantiable
class SimtStack(val parameter: SimtStackParameter)
    extends FixedIORawModule(new SimtStackInterface(parameter))
    with SerializableModule[SimtStackParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {
  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  def threadNum = parameter.threadNum
  def paddrBits = parameter.paddrBits
  def stackDepth = parameter.stackDepth

  val stack_addr = RegInit(0.U(log2Ceil(stackDepth + 1).W))
  val stack_pop_addr = RegInit(0.U(log2Ceil(stackDepth + 1).W))
  val out_diverge = RegInit(0.B)
  val out_data = io.stack
  val diverge_status = RegInit(VecInit(Seq.fill(stackDepth)(false.B)))
  val stack_rf = new RegFile(stackDepth, threadNum * 2 + paddrBits)

  // stack_pop_addr := stack_addr - 1.U
  // stack_sram.io.enable := io.push || io.pop
  // stack_sram.io.write := io.push
  // stack_sram.io.addr := Mux(io.push, stack_addr, stack_pop_addr)
  // stack_sram.io.dataIn := io.in_data.asUInt

  // when(io.push) {
  //   stack_addr := stack_addr + 1.U
  //   stack_pop_addr := stack_addr
  // }.elsewhen(io.pop && ~diverge_status(stack_pop_addr)) {
  //   stack_addr := stack_addr - 1.U
  //   stack_pop_addr := stack_pop_addr - 1.U
  // }

  // when(io.push) {
  //   diverge_status(stack_addr) := io.in_diverge
  // }.elsewhen(io.pop) {
  //   diverge_status(stack_pop_addr) := 0.B
  //   out_diverge := diverge_status(stack_pop_addr)
  // }

  // io.empty := stack_addr === 0.U
  // io.full := stack_addr === stackDepth.U
  // io.out_diverge := out_diverge
  // io.out_data := out_data

}
