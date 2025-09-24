package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}

case class WarpParameter(
  useAsyncReset: Boolean,
  clockGate:     Boolean,
  warpNum:       Int,
  stackDepth:    Int,
  xLen:          Int,
  dimNum:        Int,
  addrBits:      Int,
  pgLevels:      Int,
  asidBits:      Int,
  threadNum:     Int)
    extends SerializableModuleParameter {

  def simtStackParameter: SimtStackParameter = SimtStackParameter(
    useAsyncReset = useAsyncReset,
    clockGate = clockGate,
    threadNum = threadNum,
    addrBits = addrBits,
    stackDepth = stackDepth
  )

  def vgprIniterParameter: VGPRIniterParameter = VGPRIniterParameter(
    useAsyncReset = useAsyncReset,
    threadNum = threadNum,
    warpNum = warpNum,
    dimNum = dimNum,
    regNum = 16,
    xLen = xLen,
    addrBits = addrBits
  )

  def sgprIniterParameter: SGPRIniterParameter = SGPRIniterParameter(
    useAsyncReset = useAsyncReset,
    threadNum = threadNum,
    warpNum = warpNum,
    dimNum = dimNum,
    regNum = 16,
    xLen = xLen,
    addrBits = addrBits
  )

}

class WarpBundle(parameter: WarpParameter) extends Bundle {
  val warp_cmd = Flipped(
    DecoupledIO(new CuTaskBundle(parameter.threadNum, parameter.warpNum, parameter.dimNum, parameter.xLen))
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

  // val simt_stack = VecInit(Seq.fill(warpNum)(Module(new SimtStack(simtStackParameter)).io))
  val vgpr_writer = Module(new VGPRIniter(parameter.vgprIniterParameter))
  val sgpr_writer = Module(new SGPRIniter(parameter.sgprIniterParameter))

  // val pop_diverge = Wire(Bool())
  // val pop_data = Wire(new StackData(threadNum, addrBits))

  // the warp which accepts a new command
  val lock_warp = RegInit(0.U(log2Ceil(warpNum).W))

  // warp cmd state
  // when s_idle can accept cmd
  // then warp is in working state for regs init
  // finally return to idle
  val s_idle :: s_working :: s_finish :: Nil = Enum(3)
  val state = RegInit(s_idle)

  vgpr_writer.io.clock := io.clock
  vgpr_writer.io.reset := io.reset
  vgpr_writer.io.warp_cmd.bits := io.warp_cmd.bits
  vgpr_writer.io.warp_cmd.valid := io.warp_cmd.valid && has_idle
  vgpr_writer.io.wid := lock_warp
  vgpr_writer.io.finish.ready := state === s_finish

  sgpr_writer.io.clock := io.clock
  sgpr_writer.io.reset := io.reset
  sgpr_writer.io.warp_cmd.bits := io.warp_cmd.bits
  sgpr_writer.io.warp_cmd.valid := io.warp_cmd.valid && has_idle
  sgpr_writer.io.wid := lock_warp
  sgpr_writer.io.finish.ready := state === s_finish

  // ready信号在空闲状态时为高，只有在工作状态时才为低
  // 这样可以减少延迟，提高吞吐量
  io.warp_cmd.ready := state === s_idle & has_idle

  // temp
  vgpr_writer.io.commit_data.ready := true.B
  sgpr_writer.io.commit_data.ready := true.B

  switch(state) {
    is(s_idle) {
      when(io.warp_cmd.fire) {
        state := s_working
        lock_warp := idle_id
      }
    }

    is(s_working) {
      when(sgpr_writer.io.finish.valid && vgpr_writer.io.finish.valid) {
        state := s_finish
      }
    }

    is(s_finish) {
      state := s_idle
      // start compute unit
    }
  }
}
