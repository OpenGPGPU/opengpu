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
  addrBits:      Int,
  stackDepth:    Int)
    extends SerializableModuleParameter {
  def resetVectorBits: Int = addrBits
}

class SimtStackInterface(parameter: SimtStackParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(if (parameter.useAsyncReset) AsyncReset() else Bool())
  val resetVector = Input(Const(UInt(parameter.resetVectorBits.W)))
  val diverge_in = Input(Bool())
  val push = Input(Bool())
  val pop = Input(Bool())
  val diverge_out = Output(Bool())
  val empty = Output(Bool())
  val full = Output(Bool())
  val stack_out = new StackData(parameter.threadNum, parameter.addrBits)
  val stack_in = Flipped(new StackData(parameter.threadNum, parameter.addrBits))
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
  def addrBits = parameter.addrBits
  def stackDepth = parameter.stackDepth

  val stack_addr = RegInit(0.U(log2Ceil(stackDepth + 1).W))
  val stack_pop_addr = RegInit(0.U(log2Ceil(stackDepth + 1).W))
  val diverge_out = RegInit(0.B)
  val stack_out = io.stack_out
  val diverge_status = RegInit(VecInit(Seq.fill(stackDepth)(false.B)))

  // maybe ecc is needed for sram
  val stack_sram: SRAMInterface[Vec[UInt]] = SRAM(
    size = stackDepth,
    tpe = Vec(
      1,
      UInt((threadNum * 2 + addrBits).W)
    ),
    numReadPorts = 0,
    numWritePorts = 0,
    numReadwritePorts = 1
  )

  stack_pop_addr := stack_addr - 1.U

  stack_sram.readwritePorts.foreach { ramPort =>
    ramPort.enable := io.push || io.pop
    ramPort.isWrite := io.push
    ramPort.address := Mux(io.push, stack_addr, stack_pop_addr)
    ramPort.writeData := io.stack_in.asUInt
  }
  when(io.push) {
    stack_addr := stack_addr + 1.U
    stack_pop_addr := stack_addr
  }.elsewhen(io.pop && ~diverge_status(stack_pop_addr)) {
    stack_addr := stack_addr - 1.U
    stack_pop_addr := stack_pop_addr - 1.U
  }

  when(io.push) {
    diverge_status(stack_addr) := io.diverge_in
  }.elsewhen(io.pop) {
    diverge_status(stack_pop_addr) := 0.B
    diverge_out := diverge_status(stack_pop_addr)
  }

  io.empty := stack_addr === 0.U
  io.full := stack_addr === stackDepth.U
  io.diverge_out := diverge_out

}
