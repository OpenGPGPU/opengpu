package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.{instantiable, Instance, Instantiate}
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}
import chisel3.util.experimental.decode.DecodeBundle
import org.chipsalliance.t1.rtl.decoder.{Decoder, DecoderParam}
import ogpu.vector._
import org.chipsalliance.rocketv.{ALU, ALUParameter}

class ExecutionInterface(parameter: OGPUDecoderParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())

  // Input from Issue stage
  val in = Flipped(DecoupledIO(new Bundle {
    val warpID = UInt(log2Ceil(parameter.warpNum).W)
    val execType = UInt(2.W)
    val funct3 = UInt(3.W)
    val funct7 = UInt(7.W)
    val pc = UInt(parameter.xLen.W)
    val rs1Data = UInt(parameter.xLen.W)
    val rs2Data = UInt(parameter.xLen.W)
    val rd = UInt(5.W)
    val isRVC = Bool()
  }))

  // Execution results output
  val out = DecoupledIO(new Bundle {
    val result = UInt(parameter.xLen.W)
    val warpID = UInt(log2Ceil(parameter.warpNum).W)
    val rd = UInt(5.W)
    val exception = Bool()
  })
}

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

class RegFileIO(dataWidth: Int) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val read = Vec(
    2,
    Input(new Bundle {
      val addr = UInt(5.W)
    })
  )
  val write = Input(new Bundle {
    val addr = UInt(5.W)
    val data = UInt(dataWidth.W)
    val en = Bool()
  })
  val readData = Vec(2, Output(UInt(dataWidth.W)))
}

case class RegFileParameter(
  regNum:    Int,
  dataWidth: Int,
  zero:      Boolean = false)
    extends SerializableModuleParameter

@instantiable
class RegFile(val parameter: RegFileParameter)
    extends FixedIORawModule(new RegFileIO(parameter.dataWidth))
    with SerializableModule[RegFileParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  val rf: Mem[UInt] = Mem(parameter.regNum, UInt(parameter.dataWidth.W))
  private def access(addr: UInt): UInt = rf(addr(log2Ceil(parameter.regNum) - 1, 0))

  io.readData(0) := Mux(parameter.zero.B && io.read(0).addr === 0.U, 0.U, access(io.read(0).addr))
  io.readData(1) := Mux(parameter.zero.B && io.read(1).addr === 0.U, 0.U, access(io.read(1).addr))

  when(io.write.en && io.write.addr =/= 0.U) {
    access(io.write.addr) := io.write.data
  }
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

class WarpRegFileIO(warpNum: Int, dataWidth: Int) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val read = Vec(
    2,
    Input(new Bundle {
      val warpID = UInt(log2Ceil(warpNum).W)
      val addr = UInt(5.W)
    })
  )
  val write = Input(new Bundle {
    val warpID = UInt(log2Ceil(warpNum).W)
    val addr = UInt(5.W)
    val data = UInt(dataWidth.W)
    val en = Bool()
  })
  val readData = Vec(2, Output(UInt(dataWidth.W)))
}

case class WarpRegFileParameter(
  warpNum:   Int,
  dataWidth: Int,
  zero:      Boolean = false)
    extends SerializableModuleParameter

@instantiable
class WarpRegFile(val parameter: WarpRegFileParameter)
    extends FixedIORawModule(new WarpRegFileIO(parameter.warpNum, parameter.dataWidth))
    with SerializableModule[WarpRegFileParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // Create register file for each warp
  val regFiles =
    Seq.fill(parameter.warpNum)(Module(new RegFile(RegFileParameter(32, parameter.dataWidth, parameter.zero))))

  // Connect register file inputs
  for (i <- 0 until parameter.warpNum) {
    val writeSel = (io.write.warpID === i.U) & io.write.en
    regFiles(i).io.clock := io.clock
    regFiles(i).io.reset := io.reset
    regFiles(i).io.write.en := writeSel
    regFiles(i).io.write.addr := io.write.addr
    regFiles(i).io.write.data := io.write.data

    regFiles(i).io.read(0).addr := io.read(0).addr
    regFiles(i).io.read(1).addr := io.read(1).addr
  }

  // Create multiplexer for read output
  val readDataVec0 = VecInit(regFiles.map(_.io.readData(0)))
  val readDataVec1 = VecInit(regFiles.map(_.io.readData(1)))
  io.readData(0) := readDataVec0(io.read(0).warpID)
  io.readData(1) := readDataVec1(io.read(1).warpID)
}

@instantiable
class Execution(val parameter: OGPUDecoderParameter)
    extends FixedIORawModule(new ExecutionInterface(parameter))
    with SerializableModule[OGPUDecoderParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // ALU instance
  val alu = Module(new ALU(new ALUParameter(parameter.xLen)))

  // Pipeline control
  io.in.ready := true.B // Always ready to accept new instruction

  // Output registers
  val outValidReg = RegInit(false.B)
  val outResultReg = RegInit(0.U(parameter.xLen.W))
  val outWarpIDReg = RegInit(0.U(log2Ceil(parameter.warpNum).W))
  val outRdReg = RegInit(0.U(5.W))
  val outExceptionReg = RegInit(false.B)

  // ALU execution
  alu.io.dw := false.B
  alu.io.fn := Cat(io.in.bits.funct7(5), io.in.bits.funct3)
  alu.io.in1 := io.in.bits.rs1Data
  alu.io.in2 := io.in.bits.rs2Data

  // Register output results
  when(io.in.valid) {
    outValidReg := true.B
    outResultReg := alu.io.out
    outWarpIDReg := io.in.bits.warpID
    outRdReg := io.in.bits.rd
    outExceptionReg := false.B
  }.otherwise {
    outValidReg := false.B
  }

  // Connect output signals
  io.out.valid := outValidReg
  io.out.bits.result := outResultReg
  io.out.bits.warpID := outWarpIDReg
  io.out.bits.rd := outRdReg
  io.out.bits.exception := outExceptionReg
}
