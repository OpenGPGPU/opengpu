package ogpu.core

import chisel3._
import chisel3.util.DecoupledIO
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
}
