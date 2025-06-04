package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.SerializableModule

// Bundle for register file read ports
class RegFileReadPort(xLen: Int) extends Bundle {
  val addr = Output(UInt(5.W))
  val data = Input(UInt(xLen.W))
}

// Bundle for scoreboard interface
class ScoreboardInterface(warpNum: Int) extends Bundle {
  val busy = Input(Bool())
  val set = Output(new Bundle {
    val en = Bool()
    val warpID = UInt(log2Ceil(warpNum).W)
    val addr = UInt(5.W)
  })
  val read = Vec(
    2,
    new Bundle {
      val warpID = Output(UInt(log2Ceil(warpNum).W))
      val addr = Output(UInt(5.W))
      val busy = Input(Bool())
    }
  )
}

// Define an ExecutionType enumeration
object ExecutionType {
  val ALU = 0.U(2.W)
  val FPU = 1.U(2.W)
  val VEC = 2.U(2.W)
}

// Issue stage interface bundle
class IssueStageInterface(parameter: OGPUDecoderParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())

  // Input interface
  val in = Flipped(DecoupledIO(new Bundle {
    val instruction = new InstructionBundle(parameter.warpNum, 32)
    val coreResult = new CoreDecoderInterface(parameter)
    val rvc = Bool()
  }))

  // Output interface
  val out = DecoupledIO(new Bundle {
    val warpID = UInt(log2Ceil(parameter.warpNum).W)
    val execType = UInt(2.W) // Indicates which execution unit will handle this instruction
    val aluFn = UInt(parameter.UOPALU.width.W)
    val funct3 = UInt(3.W)
    val funct7 = UInt(7.W)
    val pc = UInt(parameter.xLen.W)
    val rs1Data = UInt(parameter.xLen.W)
    val rs2Data = UInt(parameter.xLen.W)
    val rd = UInt(5.W)
    val isRVC = Bool()
  })

  // Register file interfaces
  val intRegFile = new Bundle {
    val rs1 = new RegFileReadPort(parameter.xLen)
    val rs2 = new RegFileReadPort(parameter.xLen)
  }
  val fpRegFile = new Bundle {
    val rs1 = new RegFileReadPort(parameter.xLen)
    val rs2 = new RegFileReadPort(parameter.xLen)
  }
  val vecRegFile = new Bundle {
    val rs1 = new RegFileReadPort(parameter.vLen)
    val rs2 = new RegFileReadPort(parameter.vLen)
  }

  // Scoreboard interfaces
  val intScoreboard = new ScoreboardInterface(parameter.warpNum)
  val fpScoreboard = new ScoreboardInterface(parameter.warpNum)
  val vecScoreboard = new ScoreboardInterface(parameter.warpNum)
}

class IssueStage(val parameter: OGPUDecoderParameter)
    extends FixedIORawModule(new IssueStageInterface(parameter))
    with SerializableModule[OGPUDecoderParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // Extract instruction fields
  val inst = io.in.bits.instruction.instruction
  val rs1 = inst(19, 15)
  val rs2 = inst(24, 20)
  val rd = inst(11, 7)
  val imm = WireDefault(0.U(parameter.xLen.W))

  // Immediate decoding based on instruction format
  val immI = Cat(Fill(20, inst(31)), inst(31, 20))
  val immS = Cat(Fill(20, inst(31)), inst(31, 25), inst(11, 7))
  val immB = Cat(Fill(20, inst(31)), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W))
  val immU = Cat(inst(31, 12), Fill(12, 0.U))
  val immJ = Cat(Fill(12, inst(31)), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))

  // Select immediate based on instruction type
  val decode = io.in.bits.coreResult.output
  when(decode(parameter.selImm) === 0.U) { // I-type
    imm := immI
  }.elsewhen(decode(parameter.selImm) === 1.U) { // S-type
    imm := immS
  }.elsewhen(decode(parameter.selImm) === 2.U) { // B-type
    imm := immB
  }.elsewhen(decode(parameter.selImm) === 3.U) { // U-type
    imm := immU
  }.elsewhen(decode(parameter.selImm) === 4.U) { // J-type
    imm := immJ
  }

  // Determine if rs1/rs2 are used based on decode information
  val useRs1 = decode(parameter.selAlu1) === 1.U // RS1 is selected as ALU operand 1
  val useRs2 = decode(parameter.selAlu2) === 2.U // RS2 is selected as ALU operand 2 (UOPA2.rs2)

  // Determine register type and usage based on instruction
  val isFloatInst = decode(parameter.fren1) || decode(parameter.fwen)
  val isVectorInst = decode(parameter.vector)

  // Select appropriate scoreboard based on instruction type
  val scoreboardRead = Mux1H(
    Seq(
      (!isFloatInst && !isVectorInst) -> io.intScoreboard.read,
      (isFloatInst && !isVectorInst) -> io.fpScoreboard.read,
      isVectorInst -> io.vecScoreboard.read
    )
  )

  val scoreboardBusy = Mux1H(
    Seq(
      (!isFloatInst && !isVectorInst) -> io.intScoreboard.busy,
      (isFloatInst && !isVectorInst) -> io.fpScoreboard.busy,
      isVectorInst -> io.vecScoreboard.busy
    )
  )

  // Connect scoreboard read ports based on instruction type
  scoreboardRead(0).warpID := io.in.bits.instruction.wid
  scoreboardRead(0).addr := rs1
  scoreboardRead(1).warpID := io.in.bits.instruction.wid
  scoreboardRead(1).addr := rs2

  // Check if either needed register is busy
  val rs1Busy = scoreboardRead(0).busy && useRs1
  val rs2Busy = scoreboardRead(1).busy && useRs2
  val anyRegBusy = rs1Busy || rs2Busy

  // Modify issue logic to consider register dependencies
  val canIssue = !anyRegBusy && !scoreboardBusy

  // Add output register
  val outputReg = RegInit(0.U.asTypeOf(io.out.bits))
  val outputValidReg = RegInit(false.B)

  // Register read and output logic
  when(canIssue && io.in.valid) {
    // Set register addresses for all register files (only one will be used)
    Seq(io.intRegFile.rs1, io.fpRegFile.rs1).foreach { rf =>
      rf.addr := Mux(useRs1, rs1, 0.U)
    }
    Seq(io.intRegFile.rs2, io.fpRegFile.rs2).foreach { rf =>
      rf.addr := Mux(useRs2, rs2, 0.U)
    }
    // Vector register file might need different addressing
    io.vecRegFile.rs1.addr := Mux(useRs1, rs1, 0.U)
    io.vecRegFile.rs2.addr := Mux(useRs2, rs2, 0.U)

    // Set appropriate scoreboard based on instruction type
    io.intScoreboard.set.en := decode(parameter.wxd) && !isFloatInst && !isVectorInst
    io.fpScoreboard.set.en := decode(parameter.fwen) && !isVectorInst
    io.vecScoreboard.set.en := decode(parameter.vector)

    // Set warpID and addr for all scoreboards (only one will be enabled)
    Seq(io.intScoreboard.set, io.fpScoreboard.set, io.vecScoreboard.set).foreach { sb =>
      sb.warpID := io.in.bits.instruction.wid
      sb.addr := rd
    }

    // Prepare output data based on register type
    val rs1DataSelected = Mux1H(
      Seq(
        (!isFloatInst && !isVectorInst) -> io.intRegFile.rs1.data,
        (isFloatInst && !isVectorInst) -> io.fpRegFile.rs1.data,
        isVectorInst -> io.vecRegFile.rs1.data.asTypeOf(UInt(parameter.xLen.W))
      )
    )

    val rs2DataSelected = Mux1H(
      Seq(
        (!isFloatInst && !isVectorInst) -> io.intRegFile.rs2.data,
        (isFloatInst && !isVectorInst) -> io.fpRegFile.rs2.data,
        isVectorInst -> io.vecRegFile.rs2.data.asTypeOf(UInt(parameter.xLen.W))
      )
    )

    outputReg.rs1Data := Mux(
      useRs1,
      rs1DataSelected,
      Mux(decode(parameter.selAlu1) === 2.U, io.in.bits.instruction.pc, 0.U)
    )
    outputReg.rs2Data := Mux(
      useRs2,
      rs2DataSelected,
      Mux(decode(parameter.selAlu2) === 1.U, imm, Mux(decode(parameter.selAlu2) === 2.U, 4.U, 0.U))
    )
    outputReg.warpID := io.in.bits.instruction.wid
    outputReg.execType := Mux1H(
      Seq(
        (!isFloatInst && !isVectorInst) -> ExecutionType.ALU,
        (isFloatInst && !isVectorInst) -> ExecutionType.FPU,
        isVectorInst -> ExecutionType.VEC
      )
    )
    outputReg.funct3 := inst(14, 12)
    outputReg.funct7 := inst(31, 25)
    outputReg.aluFn := decode(parameter.aluFn)
    outputReg.pc := io.in.bits.instruction.pc
    outputReg.rd := rd
    outputReg.isRVC := io.in.bits.rvc
    outputValidReg := true.B
  }.otherwise {
    // Default values when not issuing
    Seq(io.intRegFile.rs1, io.fpRegFile.rs1, io.vecRegFile.rs1).foreach { rf =>
      rf.addr := 0.U
    }
    Seq(io.intRegFile.rs2, io.fpRegFile.rs2, io.vecRegFile.rs2).foreach { rf =>
      rf.addr := 0.U
    }

    Seq(io.intScoreboard.set, io.fpScoreboard.set, io.vecScoreboard.set).foreach { sb =>
      sb.en := false.B
      sb.warpID := 0.U
      sb.addr := 0.U
    }
    outputValidReg := false.B
  }

  // Connect output register to interface
  io.out.bits := outputReg
  io.out.valid := outputValidReg

  // Propagate ready signal directly to avoid additional latency
  // This allows new instruction to be accepted while current one is being processed
  io.in.ready := canIssue
}
