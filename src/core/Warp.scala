package ogpu.core

import chisel3._
import chisel3.util.{DecoupledIO, PriorityEncoder}
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
    extends SerializableModuleParameter

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

  val warp_idle = RegInit(VecInit(Seq.fill(parameter.warpNum)(1.B)))
  val warp_active = RegInit(VecInit(Seq.fill(parameter.warpNum)(0.B)))
  val warp_pc = RegInit(VecInit(Seq.fill(parameter.warpNum)(0.U(parameter.paddrBits.W))))
  val warp_tmask = RegInit(VecInit(Seq.fill(parameter.warpNum)(VecInit(Seq.fill(parameter.threadNum)(0.B)))))

  val pop_valid = RegInit(0.B)
  val has_idle = warp_idle.asUInt.orR
  val has_active = warp_active.asUInt.orR
  val idle_id = PriorityEncoder(warp_idle)
  val active_id = PriorityEncoder(warp_active)

}
