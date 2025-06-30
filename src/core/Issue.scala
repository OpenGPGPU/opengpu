package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.SerializableModule

// Bundle for register file read ports
class RegFileReadPort(xLen: Int) extends Bundle {
  val addr = Output(UInt(5.W))
  val data = Input(UInt(xLen.W))
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
    val fpuResult = new FPUDecoderInterface(parameter) // 新增
    val rvc = Bool()
  }))

  val aluIssue = DecoupledIO(new Bundle {
    val warpID = UInt(log2Ceil(parameter.warpNum).W)
    val execType = UInt(2.W)
    val aluFn = UInt(parameter.UOPALU.width.W)
    val funct3 = UInt(3.W)
    val funct7 = UInt(7.W)
    val pc = UInt(parameter.xLen.W)
    val rs1Data = UInt(parameter.xLen.W)
    val rs2Data = UInt(parameter.xLen.W)
    val rd = UInt(5.W)
    val isRVC = Bool()
  })

  val fpuIssue = Output(new Bundle {
    val rs1Data = UInt(parameter.xLen.W)
    val rs2Data = UInt(parameter.xLen.W)
    val rs3Data = UInt(parameter.xLen.W)
    val rnd_mode = UInt(3.W)
    val op = UInt(5.W)
    val op_mod = Bool()
    val src_fmt = UInt(2.W)
    val dst_fmt = UInt(2.W)
    val int_fmt = UInt(2.W)
    val vectorial_op = Bool()
    val tag_i = UInt(5.W)
    val flush = Bool()
    val valid = Bool()
    val warpID = UInt(log2Ceil(parameter.warpNum).W)
    val rd = UInt(5.W)
    val pc = UInt(parameter.xLen.W)
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
    val rs3 = new RegFileReadPort(parameter.xLen) // 新增
  }
  val vecRegFile = new Bundle {
    val rs1 = new RegFileReadPort(parameter.vLen)
    val rs2 = new RegFileReadPort(parameter.vLen)
  }

  // Scoreboard interfaces
  val intScoreboard = new WarpScoreboardInterface(parameter.warpNum)
  val fpScoreboard = new WarpScoreboardInterface(parameter.warpNum, 3)
  val vecScoreboard = new WarpScoreboardInterface(parameter.warpNum)
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
  val rs3 = inst(31, 27) // 新增：解析 rs3（假设为 bits 31:27，取低5位）
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
  val fpuDecode = io.in.bits.fpuResult.output // 新增
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
  val useRs3 = isFloatInst // 新增：rs3 仅用于 FPU 指令

  // Determine register type and usage based on instruction
  val isFloatInst = fpuDecode(parameter.fwen) || fpuDecode(parameter.fren1)
  val isIntInst = !isFloatInst && !isVectorInst
  val isVectorInst = decode(parameter.vector)

  // Select appropriate scoreboard based on instruction type
  val scoreboardRead = Mux1H(
    Seq(
      isIntInst -> io.intScoreboard.read,
      isFloatInst -> io.fpScoreboard.read,
      isVectorInst -> io.vecScoreboard.read
    )
  )
  val scoreboardBusy = Mux1H(
    Seq(
      isIntInst -> io.intScoreboard.busy,
      isFloatInst -> io.fpScoreboard.busy,
      isVectorInst -> io.vecScoreboard.busy
    )
  )

  // Connect scoreboard read ports based on instruction type
  scoreboardRead(0).addr := rs1
  scoreboardRead(1).addr := rs2
  scoreboardRead(2).addr := rs3 // rs3 现在已正确解析

  // Check if either needed register is busy
  val rs1Busy = scoreboardRead(0).busy && useRs1
  val rs2Busy = scoreboardRead(1).busy && useRs2
  val rs3Busy = isFloatInst && scoreboardRead(2).busy && useRs3
  val anyRegBusy = rs1Busy || rs2Busy || rs3Busy

  // Modify issue logic to consider register dependencies
  val canIssue = !anyRegBusy && !scoreboardBusy

  // Add output register for ALU
  val aluOutputReg = RegInit(0.U.asTypeOf(io.aluIssue.bits))
  val aluOutputValidReg = RegInit(false.B)

  // Add output register for FPU
  val fpuOutputReg = RegInit(0.U.asTypeOf(io.fpuIssue))
  val fpuOutputValidReg = RegInit(false.B)

  // Register read and output logic
  when(canIssue && io.in.valid) {
    // Set register addresses for all register files (only one will be used)
    Seq(io.intRegFile.rs1, io.fpRegFile.rs1).foreach { rf =>
      rf.addr := Mux(useRs1, rs1, 0.U)
    }
    Seq(io.intRegFile.rs2, io.fpRegFile.rs2).foreach { rf =>
      rf.addr := Mux(useRs2, rs2, 0.U)
    }
    io.fpRegFile.rs3.addr := Mux(useRs3, rs3, 0.U) // 新增：设置 rs3 地址
    // Vector register file might need different addressing
    io.vecRegFile.rs1.addr := Mux(useRs1, rs1, 0.U)
    io.vecRegFile.rs2.addr := Mux(useRs2, rs2, 0.U)

    // Set appropriate scoreboard based on instruction type
    io.intScoreboard.set.en := isIntInst && decode(parameter.wxd)
    io.fpScoreboard.set.en := isFloatInst && fpuDecode(parameter.fwen)
    io.vecScoreboard.set.en := isVectorInst && decode(parameter.vector)

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

    val rs3DataSelected = Mux1H( // 新增：rs3DataSelected 仅用于 FPU 三源指令
      Seq(
        (isFloatInst && !isVectorInst) -> io.fpRegFile.rs3.data,
        (!isFloatInst && !isVectorInst) -> 0.U,
        isVectorInst -> 0.U
      )
    )

    aluOutputReg.rs1Data := Mux(
      useRs1,
      rs1DataSelected,
      Mux(decode(parameter.selAlu1) === 2.U, io.in.bits.instruction.pc, 0.U)
    )
    aluOutputReg.rs2Data := Mux(
      useRs2,
      rs2DataSelected,
      Mux(decode(parameter.selAlu2) === 1.U, imm, Mux(decode(parameter.selAlu2) === 2.U, 4.U, 0.U))
    )
    aluOutputReg.warpID := io.in.bits.instruction.wid
    aluOutputReg.execType := Mux1H(
      Seq(
        (!isFloatInst && !isVectorInst) -> ExecutionType.ALU,
        (isFloatInst && !isVectorInst) -> ExecutionType.FPU,
        isVectorInst -> ExecutionType.VEC
      )
    )
    aluOutputReg.funct3 := inst(14, 12)
    aluOutputReg.funct7 := inst(31, 25)
    aluOutputReg.aluFn := decode(parameter.aluFn)
    aluOutputReg.pc := io.in.bits.instruction.pc
    aluOutputReg.rd := rd
    aluOutputReg.isRVC := io.in.bits.rvc
    aluOutputValidReg := true.B

    // FPU input signal assignments
    fpuOutputReg.rs1Data := rs1DataSelected
    fpuOutputReg.rs2Data := rs2DataSelected
    fpuOutputReg.rs3Data := rs3DataSelected // 使用解析出的 rs3 数据
    fpuOutputReg.rnd_mode := fpuDecode(parameter.rnd_mode)
    fpuOutputReg.op := fpuDecode(parameter.op)
    fpuOutputReg.op_mod := fpuDecode(parameter.op_mod)
    fpuOutputReg.src_fmt := fpuDecode(parameter.src_fmt)
    fpuOutputReg.dst_fmt := fpuDecode(parameter.dst_fmt)
    fpuOutputReg.int_fmt := fpuDecode(parameter.int_fmt)
    fpuOutputReg.vectorial_op := fpuDecode(parameter.vectorial_op)
    fpuOutputReg.tag_i := fpuDecode(parameter.tag_i)
    fpuOutputReg.flush := false.B
    fpuOutputReg.valid := aluOutputValidReg && isFloatInst && !isVectorInst
    fpuOutputReg.warpID := io.in.bits.instruction.wid
    fpuOutputReg.rd := rd
    fpuOutputReg.pc := io.in.bits.instruction.pc
    fpuOutputReg.isRVC := io.in.bits.rvc
    fpuOutputValidReg := true.B

  }.otherwise {
    // Default values when not issuing
    Seq(io.intRegFile.rs1, io.fpRegFile.rs1, io.vecRegFile.rs1).foreach { rf =>
      rf.addr := 0.U
    }
    Seq(io.intRegFile.rs2, io.fpRegFile.rs2, io.vecRegFile.rs2).foreach { rf =>
      rf.addr := 0.U
    }
    io.fpRegFile.rs3.addr := 0.U // 新增：rs3 默认值

    io.intScoreboard.set.en := false.B
    io.fpScoreboard.set.en := false.B
    io.vecScoreboard.set.en := false.B
    Seq(io.intScoreboard.set, io.fpScoreboard.set, io.vecScoreboard.set).foreach { sb =>
      sb.warpID := 0.U
      sb.addr := 0.U
    }
    aluOutputValidReg := false.B
    fpuOutputValidReg := false.B
  }

  // Connect output registers to interface
  io.aluIssue.bits := aluOutputReg
  io.aluIssue.valid := aluOutputValidReg

  io.fpuIssue := fpuOutputReg

  // Propagate ready signal directly to avoid additional latency
  io.in.ready := canIssue

  // 连接 ScoreboardInterface 的 clock/reset
  io.intScoreboard.clock := io.clock
  io.intScoreboard.reset := io.reset
  io.fpScoreboard.clock := io.clock
  io.fpScoreboard.reset := io.reset
  io.vecScoreboard.clock := io.clock
  io.vecScoreboard.reset := io.reset
}
