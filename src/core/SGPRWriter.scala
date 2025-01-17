package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}

case class SGPRWriterParameter(
  useAsyncReset: Boolean,
  threadNum:     Int,
  warpNum:       Int,
  dimNum:        Int,
  regNum:        Int,
  xLen:          Int,
  addrWidth:     Int)
    extends SerializableModuleParameter


class SGPRWriterInterface(parameter: SGPRWriterParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(if (parameter.useAsyncReset) AsyncReset() else Bool())
  val warp_cmd = Input(
    Valid(new CuTaskBundle(parameter.threadNum, parameter.warpNum, parameter.dimNum, parameter.xLen))
  )
  val wid = Input(UInt(log2Ceil(parameter.warpNum).W))
  val commit_data = DecoupledIO(
    new CommitSData(parameter.xLen, parameter.addrWidth, parameter.warpNum, parameter.regNum)
  )
  val finish = DecoupledIO(Bool())
  val idle = Output(Bool())
}

@instantiable
class SGPRWriter(val parameter: SGPRWriterParameter)
    extends FixedIORawModule(new SGPRWriterInterface(parameter))
    with SerializableModule[SGPRWriterParameter]
    with ImplicitClock
    with ImplicitReset
    with Public {
  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  def regIDWidth = log2Ceil(parameter.regNum)

  val s_idle :: s_working :: s_finish :: Nil = Enum(3)

  val commit_counter = RegInit(0.U(regIDWidth.W))
  val state = RegInit(s_idle)

  val counter_add1 = commit_counter + 1.U
  val commit_data = io.warp_cmd.bits.sgprs(commit_counter)

  io.idle := state === s_idle

  switch(state) {
    is(s_idle) {
      when(io.warp_cmd.valid) {
        state := s_working
      }
    }
    is(s_working) {
      when(((commit_counter === io.warp_cmd.bits.sgpr_num) & io.commit_data.fire) | io.warp_cmd.bits.sgpr_num === 0.U) {
        state := s_finish
      }
    }
    is(s_finish) {
      when(io.finish.fire) {
        state := s_idle
      }
    }
  }

  io.commit_data.bits.wid := io.wid
  io.commit_data.bits.rd := commit_counter
  io.commit_data.bits.pc := 0.U
  io.commit_data.bits.data := commit_data
  io.commit_data.bits.mask := io.warp_cmd.bits.mask(0)
  io.finish.valid := state === s_finish
}
