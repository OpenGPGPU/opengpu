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

  // Inputs from decode stage
  val coreResult = Flipped(DecoupledIO(new CoreDecoderInterface(parameter)))
  // val fpuResult = Flipped(DecoupledIO(new FPUDecoderInterface(parameter)))
  // val vectorResult = Flipped(DecoupledIO(new DecodeBundle(Decoder.allFields(parameter.vector_decode_param))))
  val instruction_in = Input(new InstructionBundle(parameter.warpNum, 32))
  val rvc = Input(Bool()) // Add RVC input signal

  // Execution results output
  val execResult = DecoupledIO(new Bundle {
    val result = UInt(parameter.xLen.W)
    val wid = UInt(log2Ceil(parameter.warpNum).W)
    val valid = Bool()
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

  // Instantiate scoreboard and register file
  val scoreboard = Module(new WarpScoreboard(WarpScoreboardParameter(parameter.warpNum, 32, true)))
  val regFile = Module(new WarpRegFile(WarpRegFileParameter(parameter.warpNum, parameter.xLen, true)))
  val alu = Module(new ALU(new ALUParameter(parameter.xLen)))

  // Connect clock and reset
  scoreboard.io.clock := io.clock
  scoreboard.io.reset := io.reset
  regFile.io.clock := io.clock
  regFile.io.reset := io.reset

  // Initialize scoreboard inputs with default values
  scoreboard.io.set.en := false.B
  scoreboard.io.set.warpID := 0.U
  scoreboard.io.set.addr := 0.U
  scoreboard.io.clear.en := false.B
  scoreboard.io.clear.warpID := 0.U
  scoreboard.io.clear.addr := 0.U
  // Update scoreboard read signals
  scoreboard.io.read.warpID := io.instruction_in.wid
  scoreboard.io.read.addr := io.instruction_in.instruction(19, 15) // rs1
  scoreboard.io.readBypassed.warpID := io.instruction_in.wid
  scoreboard.io.readBypassed.addr := io.instruction_in.instruction(24, 20) // rs2

  // Initialize register file inputs with default values
  regFile.io.read(0).warpID := 0.U
  regFile.io.read(0).addr := 0.U
  regFile.io.read(1).warpID := 0.U
  regFile.io.read(1).addr := 0.U
  regFile.io.write.warpID := 0.U
  regFile.io.write.addr := 0.U
  regFile.io.write.data := 0.U
  regFile.io.write.en := false.B

  // Pipeline registers
  val decodeReg = Reg(new Bundle {
    val valid = Bool()
    val warpID = UInt(log2Ceil(parameter.warpNum).W)
    val instruction = UInt(32.W)
    val pc = UInt(parameter.xLen.W)
    val rs1 = UInt(5.W)
    val rs2 = UInt(5.W)
    val rd = UInt(5.W)
    val imm = UInt(parameter.xLen.W)
    val funct3 = UInt(3.W)
    val funct7 = UInt(7.W)
    val isRVC = Bool() // Add RVC status to decode stage register
  })

  val executeReg = Reg(new Bundle {
    val valid = Bool()
    val warpID = UInt(log2Ceil(parameter.warpNum).W)
    val instruction = UInt(32.W)
    val pc = UInt(parameter.xLen.W)
    val rs1Data = UInt(parameter.xLen.W)
    val rs2Data = UInt(parameter.xLen.W)
    val rd = UInt(5.W)
    val imm = UInt(parameter.xLen.W)
    val funct3 = UInt(3.W)
    val funct7 = UInt(7.W)
    val isRVC = Bool() // Update execute stage register
  })

  val writebackReg = Reg(new Bundle {
    val valid = Bool()
    val warpID = UInt(log2Ceil(parameter.warpNum).W)
    val instruction = UInt(32.W)
    val pc = UInt(parameter.xLen.W)
    val result = UInt(parameter.xLen.W)
    val rd = UInt(5.W)
    val exception = Bool()
  })

  // Stall logic
  val stall = scoreboard.io.busy || scoreboard.io.busyBypassed
  io.coreResult.ready := !stall

  // Decode stage
  when(io.coreResult.valid && !stall) { // Only block new instructions when stalled
    decodeReg.valid := true.B
    decodeReg.warpID := io.instruction_in.wid
    decodeReg.instruction := io.instruction_in.instruction
    decodeReg.pc := io.instruction_in.pc
    decodeReg.rs1 := io.instruction_in.instruction(19, 15)
    decodeReg.rs2 := io.instruction_in.instruction(24, 20)
    decodeReg.rd := io.instruction_in.instruction(11, 7)
    decodeReg.funct3 := io.instruction_in.instruction(14, 12)
    decodeReg.funct7 := io.instruction_in.instruction(31, 25)
    decodeReg.isRVC := io.rvc // Update decode stage

    // Update register file read signals for both rs1 and rs2
    when(io.coreResult.valid && !stall) {
      // First read port for rs1
      regFile.io.read(0).warpID := io.instruction_in.wid
      regFile.io.read(0).addr := io.instruction_in.instruction(19, 15) // rs1

      // Second read port for rs2
      regFile.io.read(1).warpID := io.instruction_in.wid
      regFile.io.read(1).addr := io.instruction_in.instruction(24, 20) // rs2
    }

    // Sign extend immediate
    val imm = io.instruction_in.instruction
    decodeReg.imm := MuxCase(
      0.U(parameter.xLen.W),
      Seq(
        (imm(6, 0) === "b0110011".U) -> 0.U, // R-type
        (imm(6, 0) === "b0010011".U) -> Cat(Fill(20, imm(31)), imm(31, 20)), // I-type
        (imm(6, 0) === "b0100011".U) -> Cat(Fill(20, imm(31)), imm(31, 25), imm(11, 7)), // S-type
        (imm(6, 0) === "b1100011".U) -> Cat(
          Fill(19, imm(31)),
          imm(31),
          imm(7),
          imm(30, 25),
          imm(11, 8),
          0.U(1.W)
        ), // B-type
        (imm(6, 0) === "b1101111".U) -> Cat(
          Fill(11, imm(31)),
          imm(31),
          imm(19, 12),
          imm(20),
          imm(30, 21),
          0.U(1.W)
        ), // J-type
        (imm(6, 0) === "b1100111".U) -> Cat(Fill(20, imm(31)), imm(31, 20)) // I-type (JALR)
      )
    )
  }.otherwise {
    decodeReg.valid := false.B
  }

  // Execute stage
  when(decodeReg.valid) { // Execute stage continues regardless of stall
    executeReg.valid := true.B
    executeReg.warpID := decodeReg.warpID
    executeReg.instruction := decodeReg.instruction
    executeReg.pc := decodeReg.pc
    executeReg.rd := decodeReg.rd
    executeReg.imm := decodeReg.imm
    executeReg.funct3 := decodeReg.funct3
    executeReg.funct7 := decodeReg.funct7
    executeReg.isRVC := decodeReg.isRVC // Update execute stage register

    // Read register values
    executeReg.rs1Data := regFile.io.readData(0) // Data from first read port
    executeReg.rs2Data := regFile.io.readData(1) // Data from second read port

    // Set scoreboard for destination register
    scoreboard.io.set.en := true.B
    scoreboard.io.set.warpID := decodeReg.warpID
    scoreboard.io.set.addr := decodeReg.rd
  }.otherwise {
    executeReg.valid := false.B
  }

  // ALU execution
  alu.io.dw := false.B
  alu.io.fn := Cat(executeReg.funct7(5), executeReg.funct3)
  alu.io.in1 := executeReg.rs1Data
  alu.io.in2 := Mux(executeReg.instruction(6, 0) === "b0010011".U, executeReg.imm, executeReg.rs2Data)

  // Branch comparison logic
  val isBranch = executeReg.instruction(6, 0) === "b1100011".U
  val isJump = executeReg.instruction(6, 0) === "b1101111".U || // JAL
    executeReg.instruction(6, 0) === "b1100111".U // JALR

  // Set ALU function for branch comparison
  when(isBranch) {
    // ALU function encoding for branch instructions:
    // funct3[2:0]  Meaning
    // 000          BEQ
    // 001          BNE
    // 100          BLT
    // 101          BGE
    // 110          BLTU
    // 111          BGEU
    alu.io.fn := Cat(false.B, executeReg.funct3)
  }

  // Branch target calculation
  val branchOffset = executeReg.imm
  val branchTarget = executeReg.pc + branchOffset

  // Use ALU comparison output for branch decision
  val branchTaken = isBranch && alu.io.cmp_out

  // Jump and branch target selection
  val jumpTarget = Mux(
    executeReg.instruction(6, 0) === "b1100111".U, // JALR
    (executeReg.rs1Data + executeReg.imm) & ~1.U, // Clear least significant bit for JALR
    Mux(
      executeReg.instruction(6, 0) === "b1101111".U, // JAL
      executeReg.pc + executeReg.imm,
      branchTarget
    )
  )

  // PC increment based on instruction type
  val instBytes = Mux(executeReg.isRVC, 2.U, 4.U)
  val nextPC = Mux(isJump || branchTaken, jumpTarget, executeReg.pc + instBytes)

  // Writeback stage
  when(executeReg.valid) { // Writeback stage continues regardless of stall
    writebackReg.valid := true.B
    writebackReg.warpID := executeReg.warpID
    writebackReg.instruction := executeReg.instruction
    writebackReg.pc := executeReg.pc
    writebackReg.rd := executeReg.rd
    writebackReg.result := alu.io.out
    writebackReg.exception := false.B

    // Write result to register file
    regFile.io.write.warpID := executeReg.warpID
    regFile.io.write.addr := executeReg.rd
    regFile.io.write.data := alu.io.out
    regFile.io.write.en := true.B

    // Clear scoreboard
    scoreboard.io.clear.en := true.B
    scoreboard.io.clear.warpID := executeReg.warpID
    scoreboard.io.clear.addr := executeReg.rd
  }.otherwise {
    writebackReg.valid := false.B
  }

  // Output results
  io.execResult.valid := writebackReg.valid
  io.execResult.bits.result := writebackReg.result
  io.execResult.bits.wid := writebackReg.warpID
  io.execResult.bits.valid := writebackReg.valid
  io.execResult.bits.exception := writebackReg.exception

  // Exception handling
  val illegalInstruction = !decodeReg.valid && io.coreResult.valid
  val misalignedPC = decodeReg.pc(1, 0) =/= 0.U
}
