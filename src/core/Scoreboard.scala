package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.{instantiable, Instance, Instantiate}
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}
import chisel3.util.experimental.decode.DecodeBundle

class ScoreboardIO extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val set = Input(new Bundle {
    val en = Bool()
    val addr = UInt(5.W)
  })
  val clear = Input(new Bundle {
    val en = Bool()
    val addr = UInt(5.W)
  })
  val read = Input(new Bundle {
    val addr = UInt(5.W)
  })
  val readBypassed = Input(new Bundle {
    val addr = UInt(5.W)
  })
  val busy = Output(Bool())
  val busyBypassed = Output(Bool())
}

case class ScoreboardParameter(
  regNum: Int,
  zero:   Boolean = false)
    extends SerializableModuleParameter

@instantiable
class Scoreboard(val parameter: ScoreboardParameter)
    extends FixedIORawModule(new ScoreboardIO)
    with SerializableModule[ScoreboardParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  private val _r = RegInit(0.U(parameter.regNum.W))
  private val r = if (parameter.zero) (_r >> 1 << 1) else _r
  private val _next = RegInit(0.U(parameter.regNum.W))
  private val ens = RegInit(false.B)

  private def mask(en: Bool, addr: UInt) = Mux(en, 1.U << addr, 0.U)

  when(io.set.en) {
    _next := _next | mask(io.set.en, io.set.addr)
    ens := true.B
  }

  when(io.clear.en) {
    _next := _next & ~mask(io.clear.en, io.clear.addr)
    ens := true.B
  }

  when(ens) {
    _r := _next
    ens := false.B
  }

  io.busy := r(io.read.addr)
  io.busyBypassed := _next(io.readBypassed.addr)
}

class WarpScoreboardIO(warpNum: Int, regNum: Int) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val set = Input(new Bundle {
    val en = Bool()
    val warpID = UInt(log2Ceil(warpNum).W)
    val addr = UInt(log2Ceil(regNum).W)
  })
  val clear = Input(new Bundle {
    val en = Bool()
    val warpID = UInt(log2Ceil(warpNum).W)
    val addr = UInt(log2Ceil(regNum).W)
  })
  val read = Input(new Bundle {
    val warpID = UInt(log2Ceil(warpNum).W)
    val addr = UInt(log2Ceil(regNum).W)
  })
  val readBypassed = Input(new Bundle {
    val warpID = UInt(log2Ceil(warpNum).W)
    val addr = UInt(log2Ceil(regNum).W)
  })
  val busy = Output(Bool())
  val busyBypassed = Output(Bool())
}

case class WarpScoreboardParameter(
  warpNum: Int,
  regNum:  Int,
  zero:    Boolean = false)
    extends SerializableModuleParameter

@instantiable
class WarpScoreboard(val parameter: WarpScoreboardParameter)
    extends FixedIORawModule(new WarpScoreboardIO(parameter.warpNum, parameter.regNum))
    with SerializableModule[WarpScoreboardParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // Create scoreboard for each warp
  val scoreboards =
    Seq.fill(parameter.warpNum)(Module(new Scoreboard(ScoreboardParameter(parameter.regNum, parameter.zero))))

  // Connect scoreboard inputs
  for (i <- 0 until parameter.warpNum) {
    scoreboards(i).io.clock := io.clock
    scoreboards(i).io.reset := io.reset

    val warpSel = (io.set.warpID === i.U) & io.set.en
    scoreboards(i).io.set.en := warpSel
    scoreboards(i).io.set.addr := io.set.addr

    val clearSel = (io.clear.warpID === i.U) & io.clear.en
    scoreboards(i).io.clear.en := clearSel
    scoreboards(i).io.clear.addr := io.clear.addr

    scoreboards(i).io.read.addr := io.read.addr
    scoreboards(i).io.readBypassed.addr := io.readBypassed.addr
  }

  // Create multiplexers for read outputs
  val busyVec = VecInit(scoreboards.map(_.io.busy))
  val busyBypassedVec = VecInit(scoreboards.map(_.io.busyBypassed))

  io.busy := busyVec(io.read.warpID)
  io.busyBypassed := busyBypassedVec(io.readBypassed.warpID)
}
