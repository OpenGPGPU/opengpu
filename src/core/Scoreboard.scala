package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}

case class WarpScoreboardParameter(
  warpNum: Int,
  regNum:  Int,
  zero:    Boolean = false,
  opNum:   Int = 2)
    extends SerializableModuleParameter

case class ScoreboardParameter(
  regNum: Int,
  zero:   Boolean = false,
  opNum:  Int = 2)
    extends SerializableModuleParameter

class ScoreboardIO(val parameter: ScoreboardParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val set = Input(new Bundle {
    val en = Bool()
    val addr = UInt(log2Ceil(parameter.regNum).W)
  })
  val clear = Input(new Bundle {
    val en = Bool()
    val addr = UInt(log2Ceil(parameter.regNum).W)
  })
  val read = Vec(
    parameter.opNum,
    Input(new Bundle {
      val addr = UInt(log2Ceil(parameter.regNum).W)
    })
  )
  val busy = Output(Vec(parameter.opNum, Bool()))
}

@instantiable
class Scoreboard(val parameter: ScoreboardParameter)
    extends FixedIORawModule(new ScoreboardIO(parameter))
    with SerializableModule[ScoreboardParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  private val _r = RegInit(0.U(parameter.regNum.W))

  val setMask = Mux(io.set.en, 1.U << io.set.addr, 0.U)
  val clearMask = Mux(io.clear.en, 1.U << io.clear.addr, 0.U)
  _r := (_r & ~clearMask) | setMask

  for (i <- 0 until parameter.opNum) {
    io.busy(i) := _r(io.read(i).addr)
  }
}

// Bundle for scoreboard interface
class ScoreboardInterface(val parameter: ScoreboardParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val busy = Input(Bool())
  val set = Output(new Bundle {
    val en = Bool()
    val warpID = UInt(log2Ceil(parameter.regNum).W)
    val addr = UInt(5.W)
  })
  val read = Vec(
    parameter.opNum,
    new Bundle {
      val addr = Output(UInt(5.W))
      val busy = Input(Bool())
    }
  )
}

// Bundle for warp scoreboard interface
class WarpScoreboardInterface(val parameter: WarpScoreboardParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val busy = Input(Bool())
  val set = Output(new Bundle {
    val en = Bool()
    val warpID = UInt(log2Ceil(parameter.warpNum).W)
    val addr = UInt(5.W)
  })
  val clear = Output(new Bundle {
    val en = Bool()
    val warpID = UInt(log2Ceil(parameter.warpNum).W)
    val addr = UInt(5.W)
  })
  val read = Vec(
    parameter.opNum,
    new Bundle {
      val addr = Output(UInt(5.W))
      val busy = Input(Bool())
    }
  )
}

@instantiable
class WarpScoreboard(val parameter: WarpScoreboardParameter)
    extends FixedIORawModule(new WarpScoreboardInterface(parameter))
    with SerializableModule[WarpScoreboardParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // Create scoreboard for each warp
  val scoreboards =
    Seq.fill(parameter.warpNum)(
      Module(new Scoreboard(ScoreboardParameter(parameter.regNum, parameter.zero, parameter.opNum)))
    )

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

    for (j <- 0 until parameter.opNum) {
      scoreboards(i).io.read(j).addr := io.read(j).addr
    }
  }

  // Create multiplexers for read outputs
  for (j <- 0 until parameter.opNum) {
    val busyVec = VecInit(scoreboards.map(_.io.busy(j)))
    io.read(j).busy := busyVec(io.set.warpID)
  }
}
