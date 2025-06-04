package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.{instantiable, Instance, Instantiate}
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}
import chisel3.util.experimental.decode.DecodeBundle
import org.chipsalliance.t1.rtl.decoder.{Decoder, DecoderParam}

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
