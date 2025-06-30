package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}

class ScoreboardIO(val opNum: Int = 2) extends Bundle {
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
  val read = Vec(
    opNum,
    Input(new Bundle {
      val addr = UInt(5.W)
    })
  )
  val busy = Output(Vec(opNum, Bool()))
  val readBypassed = Input(new Bundle {
    val addr = UInt(5.W)
  })
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

  // busy = 当前busy | 本周期set/clear后的busy
  for (i <- 0 until io.read.length) {
    val busyNow = r(io.read(i).addr)
    val busyBypassed = _next(io.read(i).addr)
    io.busy(i) := busyNow | busyBypassed
  }
}

// Bundle for scoreboard interface
class ScoreboardInterface(warpNum: Int, opNum: Int = 2) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val busy = Input(Bool())
  val set = Output(new Bundle {
    val en = Bool()
    val warpID = UInt(log2Ceil(warpNum).W)
    val addr = UInt(5.W)
  })
  val read = Vec(
    opNum,
    new Bundle {
      val addr = Output(UInt(5.W))
      val busy = Input(Bool())
    }
  )
}

// Bundle for warp scoreboard interface
class WarpScoreboardInterface(warpNum: Int, opNum: Int = 2) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val busy = Input(Bool())
  val set = Output(new Bundle {
    val en = Bool()
    val warpID = UInt(log2Ceil(warpNum).W)
    val addr = UInt(5.W)
  })
  val clear = Output(new Bundle { // 恢复 clear 信号
    val en = Bool()
    val warpID = UInt(log2Ceil(warpNum).W)
    val addr = UInt(5.W)
  })
  val read = Vec(
    opNum,
    new Bundle {
      val addr = Output(UInt(5.W))
      val busy = Input(Bool())
    }
  )
}

case class WarpScoreboardParameter(
  warpNum: Int,
  regNum:  Int,
  zero:    Boolean = false)
    extends SerializableModuleParameter

@instantiable
class WarpScoreboard(val parameter: WarpScoreboardParameter)
    extends FixedIORawModule(new WarpScoreboardInterface(parameter.warpNum, 2))
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

    for (j <- 0 until io.read.length) {
      scoreboards(i).io.read(j).addr := io.read(j).addr
    }
  }

  // Create multiplexers for read outputs
  for (j <- 0 until io.read.length) {
    val busyVec = VecInit(scoreboards.map(_.io.busy(j)))
    io.read(j).busy := busyVec(io.set.warpID)
  }
}
