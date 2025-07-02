package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}

class RegFileReadIO(dataWidth: Int, opNum: Int = 2) extends Bundle {
  val read = Vec(
    opNum,
    Input(new Bundle {
      val addr = UInt(5.W)
    })
  )
  val readData = Vec(opNum, Output(UInt(dataWidth.W)))
}

class RegFileIO(dataWidth: Int, opNum: Int = 2) extends RegFileReadIO(dataWidth, opNum) {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val write = Input(new Bundle {
    val addr = UInt(5.W)
    val data = UInt(dataWidth.W)
    val en = Bool()
  })
}

case class RegFileParameter(
  regNum:    Int,
  dataWidth: Int,
  opNum:     Int = 2,
  zero:      Boolean = false)
    extends SerializableModuleParameter

@instantiable
class RegFile(val parameter: RegFileParameter)
    extends FixedIORawModule(new RegFileIO(parameter.dataWidth, parameter.opNum))
    with SerializableModule[RegFileParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  val rf: Mem[UInt] = Mem(parameter.regNum, UInt(parameter.dataWidth.W))
  private def access(addr: UInt): UInt = rf(addr(log2Ceil(parameter.regNum) - 1, 0))

  // 为每个读端口生成对应的读取逻辑
  for (i <- 0 until parameter.opNum) {
    io.readData(i) := Mux(parameter.zero.B && io.read(i).addr === 0.U, 0.U, access(io.read(i).addr))
  }

  when(io.write.en && io.write.addr =/= 0.U) {
    access(io.write.addr) := io.write.data
  }
}
class WarpRegFileIO(warpNum: Int, dataWidth: Int, opNum: Int = 2) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val read = Vec(
    opNum,
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
  val readData = Vec(opNum, Output(UInt(dataWidth.W)))
}

case class WarpRegFileParameter(
  warpNum:   Int,
  dataWidth: Int,
  opNum:     Int = 2,
  zero:      Boolean = false)
    extends SerializableModuleParameter

@instantiable
class WarpRegFile(val parameter: WarpRegFileParameter)
    extends FixedIORawModule(new WarpRegFileIO(parameter.warpNum, parameter.dataWidth, parameter.opNum))
    with SerializableModule[WarpRegFileParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // Create register file for each warp
  val regFiles =
    Seq.fill(parameter.warpNum)(
      Module(new RegFile(RegFileParameter(32, parameter.dataWidth, parameter.opNum, parameter.zero)))
    )

  // Connect register file inputs
  for (i <- 0 until parameter.warpNum) {
    val writeSel = (io.write.warpID === i.U) & io.write.en
    regFiles(i).io.clock := io.clock
    regFiles(i).io.reset := io.reset
    regFiles(i).io.write.en := writeSel
    regFiles(i).io.write.addr := io.write.addr
    regFiles(i).io.write.data := io.write.data

    for (j <- 0 until parameter.opNum) {
      regFiles(i).io.read(j).addr := io.read(j).addr
    }
  }

  // Create multiplexer for read output
  for (j <- 0 until parameter.opNum) {
    val readDataVec = VecInit(regFiles.map(_.io.readData(j)))
    io.readData(j) := readDataVec(io.read(j).warpID)
  }
}
