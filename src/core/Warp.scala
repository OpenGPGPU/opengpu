package ogpu.core

import chisel3._
import chisel3.util.{log2Ceil, DecoupledIO, PriorityEncoder}
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}

case class WarpParameter(
  useAsyncReset: Boolean,
  clockGate:     Boolean,
  warpNum:       Int,
  stackDepth:    Int,
  xLen:          Int,
  dimNum:        Int,
  paddrBits:     Int,
  pgLevels:      Int,
  asidBits:      Int,
  threadNum:     Int)
    extends SerializableModuleParameter {

  def addrBits = paddrBits
  def simtStackParameter: SimtStackParameter = SimtStackParameter(
    useAsyncReset = useAsyncReset,
    clockGate = clockGate,
    threadNum = threadNum,
    addrBits = addrBits,
    stackDepth = stackDepth
  )
}

class WarpInterface(parameter: WarpParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(if (parameter.useAsyncReset) AsyncReset() else Bool())
  val warp_cmd = Flipped(
    DecoupledIO(new CuTaskBundle(parameter.threadNum, parameter.warpNum, parameter.dimNum, parameter.xLen))
  )
}

@instantiable
class WarpScheduler(val parameter: WarpParameter)
    extends FixedIORawModule(new WarpInterface(parameter))
    with SerializableModule[WarpParameter]
    with ImplicitClock
    with ImplicitReset
    with Public {
  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  def simtStackParameter = parameter.simtStackParameter
  def warpNum = parameter.warpNum
  def addrBits = parameter.addrBits
  def threadNum = parameter.threadNum

  val warp_idle = RegInit(VecInit(Seq.fill(warpNum)(1.B)))
  val warp_active = RegInit(VecInit(Seq.fill(warpNum)(0.B)))
  val warp_pc = RegInit(VecInit(Seq.fill(warpNum)(0.U(addrBits.W))))
  val warp_tmask = RegInit(VecInit(Seq.fill(warpNum)(VecInit(Seq.fill(threadNum)(0.B)))))

  val pop_valid = RegInit(0.B)
  val pop_wid = RegInit(0.U(log2Ceil(warpNum).W))

  val has_idle = warp_idle.asUInt.orR
  val has_active = warp_active.asUInt.orR
  val idle_id = PriorityEncoder(warp_idle)
  val active_id = PriorityEncoder(warp_active)

  val simt_stack = VecInit(Seq.fill(warpNum)(Module(new SimtStack(simtStackParameter)).io))

  val pop_diverge = Wire(Bool())
  val pop_data = Wire(new StackData(threadNum, addrBits))

  // the warp which accepts a new command
  val lock_warp = RegInit(0.U(log2Ceil(warpNum).W))
  val writer_finish = RegInit(false.B)

}
