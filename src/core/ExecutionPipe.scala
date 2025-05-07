package ogpu.core

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.{instantiable, Instance, Instantiate}
import chisel3.experimental.SerializableModule
import chisel3.util.experimental.decode.DecodeBundle
import org.chipsalliance.t1.rtl.decoder.{Decoder, DecoderParam}
import ogpu.vector._
import org.chipsalliance.rocketv.{ALU, ALUParameter}

class ExecutionInterface(parameter: OGPUDecoderParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())

  // Inputs from decode stage
  val coreResult = Flipped(DecoupledIO(new CoreDecoderInterface(parameter)))
  val fpuResult = Flipped(DecoupledIO(new FPUDecoderInterface(parameter)))
  val vectorResult = Flipped(DecoupledIO(new DecodeBundle(Decoder.allFields(parameter.vector_decode_param))))
  val instruction_in = Input(new InstructionBundle(parameter.warpNum, 32))

  // Execution results output
  val execResult = DecoupledIO(new Bundle {
    val result = UInt(parameter.xLen.W)
    val wid = UInt(log2Ceil(parameter.warpNum).W)
    val valid = Bool()
    val exception = Bool()
  })

  // Debug interface
  // val debug = new Bundle {
  //   val pc = Output(UInt(parameter.xLen.W))
  //   val instruction = Output(UInt(32.W))
  //   val warpID = Output(UInt(log2Ceil(parameter.warpNum).W))
  //   val rs1Data = Output(UInt(parameter.xLen.W))
  //   val rs2Data = Output(UInt(parameter.xLen.W))
  //   val rdData = Output(UInt(parameter.xLen.W))
  //   val branchTaken = Output(Bool())
  //   val jumpTarget = Output(UInt(parameter.xLen.W))
  //   val misprediction = Output(Bool())
  //   val stall = Output(Bool())
  //   val scoreboard = Output(Vec(parameter.warpNum, UInt(32.W)))
  // }
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
  val scoreboard = new WarpScoreboard(32, parameter.warpNum, true)
  val regFile = new WarpRegFile(32, parameter.xLen, parameter.warpNum, true)
  val alu = Module(new ALU(new ALUParameter(parameter.xLen)))

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

  // Decode stage
  when(io.coreResult.valid) {
    decodeReg.valid := true.B
    decodeReg.warpID := io.instruction_in.wid
    decodeReg.instruction := io.instruction_in.instruction
    decodeReg.pc := io.instruction_in.pc
    decodeReg.rs1 := io.instruction_in.instruction(19, 15)
    decodeReg.rs2 := io.instruction_in.instruction(24, 20)
    decodeReg.rd := io.instruction_in.instruction(11, 7)
    decodeReg.funct3 := io.instruction_in.instruction(14, 12)
    decodeReg.funct7 := io.instruction_in.instruction(31, 25)

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
  when(decodeReg.valid) {
    executeReg.valid := true.B
    executeReg.warpID := decodeReg.warpID
    executeReg.instruction := decodeReg.instruction
    executeReg.pc := decodeReg.pc
    executeReg.rd := decodeReg.rd
    executeReg.imm := decodeReg.imm
    executeReg.funct3 := decodeReg.funct3
    executeReg.funct7 := decodeReg.funct7

    // Read register values
    executeReg.rs1Data := regFile.read(decodeReg.warpID, decodeReg.rs1)
    executeReg.rs2Data := regFile.read(decodeReg.warpID, decodeReg.rs2)

    // Set scoreboard for destination register
    scoreboard.set(true.B, decodeReg.warpID, decodeReg.rd)
  }.otherwise {
    executeReg.valid := false.B
  }

  // ALU execution
  alu.io.dw := false.B
  alu.io.fn := Cat(executeReg.funct7(5), executeReg.funct3)
  alu.io.in1 := executeReg.rs1Data
  alu.io.in2 := Mux(executeReg.instruction(6, 0) === "b0010011".U, executeReg.imm, executeReg.rs2Data)

  // Writeback stage
  when(executeReg.valid) {
    writebackReg.valid := true.B
    writebackReg.warpID := executeReg.warpID
    writebackReg.instruction := executeReg.instruction
    writebackReg.pc := executeReg.pc
    writebackReg.rd := executeReg.rd
    writebackReg.result := alu.io.out
    writebackReg.exception := false.B

    // Write result to register file
    regFile.write(executeReg.warpID, executeReg.rd, alu.io.out)

    // Clear scoreboard
    scoreboard.clear(true.B, executeReg.warpID, executeReg.rd)
  }.otherwise {
    writebackReg.valid := false.B
  }

  // Output results
  io.execResult.valid := writebackReg.valid
  io.execResult.bits.result := writebackReg.result
  io.execResult.bits.wid := writebackReg.warpID
  io.execResult.bits.valid := writebackReg.valid
  io.execResult.bits.exception := writebackReg.exception

  // Stall logic
  val stall = scoreboard.read(decodeReg.warpID, decodeReg.rs1) ||
    scoreboard.read(decodeReg.warpID, decodeReg.rs2)
  io.coreResult.ready := !stall

  // Branch and jump handling
  val isBranch = executeReg.instruction(6, 0) === "b1100011".U
  val isJump = executeReg.instruction(6, 0) === "b1101111".U || // JAL
    executeReg.instruction(6, 0) === "b1100111".U // JALR

  val branchTaken = alu.io.cmp_out && isBranch
  val jumpTarget = Mux(
    executeReg.instruction(6, 0) === "b1101111".U,
    executeReg.pc + executeReg.imm,
    executeReg.rs1Data + executeReg.imm
  )

  val jumpTaken = isJump || (isBranch && branchTaken)
  val nextPC = Mux(jumpTaken, jumpTarget, executeReg.pc + 4.U)

  // Flush pipeline on jump/branch
  when(executeReg.valid && jumpTaken) {
    decodeReg.valid := false.B
    executeReg.valid := false.B
  }

  // Exception handling
  val illegalInstruction = !decodeReg.valid && io.coreResult.valid
  val misalignedPC = decodeReg.pc(1, 0) =/= 0.U

  // Connect debug signals
  // io.debug.pc := executeReg.pc
  // io.debug.instruction := executeReg.instruction
  // io.debug.warpID := executeReg.warpID
  // io.debug.rs1Data := executeReg.rs1Data
  // io.debug.rs2Data := executeReg.rs2Data
  // io.debug.rdData := writebackReg.result
  // io.debug.branchTaken := branchTaken
  // io.debug.jumpTarget := jumpTarget
  // io.debug.stall := stall

  class Scoreboard(n: Int, zero: Boolean = false) {
    def set(en:            Bool, addr: UInt): Unit = update(en, _next | mask(en, addr))
    def clear(en:          Bool, addr: UInt): Unit = update(en, _next & ~mask(en, addr))
    def read(addr:         UInt): Bool = r(addr)
    def readBypassed(addr: UInt): Bool = _next(addr)

    private val _r = RegInit(0.U(n.W))
    private val r = if (zero) (_r >> 1 << 1) else _r
    private var _next = r
    private var ens = false.B
    private def mask(en: Bool, addr: UInt) = Mux(en, 1.U << addr, 0.U)
    private def update(en: Bool, update: UInt) = {
      _next = update
      ens = ens || en
      when(ens) { _r := _next }
    }
  }

  class RegFile(n: Int, w: Int, zero: Boolean = false) {
    val rf: Mem[UInt] = Mem(n, UInt(w.W))
    private def access(addr: UInt): UInt = rf(~addr(log2Ceil(n) - 1, 0))
    private val reads = collection.mutable.ArrayBuffer[(UInt, UInt)]()
    private var canRead = true
    def read(addr: UInt) = {
      require(canRead)
      reads += addr -> Wire(UInt())
      reads.last._2 := Mux(zero.B && addr === 0.U, 0.U, access(addr))
      reads.last._2
    }
    def write(addr: UInt, data: UInt): Unit = {
      canRead = false
      when(addr =/= 0.U) {
        access(addr) := data
        for ((raddr, rdata) <- reads)
          when(addr === raddr) { rdata := data }
      }
    }
  }

  class WarpScoreboard(n: Int, warpNum: Int, zero: Boolean = false) {
    val scoreboards = Seq.fill(warpNum)(new Scoreboard(n, zero))

    def set(en: Bool, warpID: UInt, addr: UInt): Unit = {
      for (i <- 0 until warpNum) {
        val new_en = (warpID === i.U) & en
        scoreboards(i).set(new_en, addr)
      }
    }

    def clear(en: Bool, warpID: UInt, addr: UInt): Unit = {
      for (i <- 0 until warpNum) {
        val new_en = (warpID === i.U) & en
        scoreboards(i).clear(new_en, addr)
      }
    }

    def read(warpID: UInt, addr: UInt): Bool = {
      val results = VecInit(Seq.fill(warpNum)(Wire(Bool())))
      for (i <- 0 until warpNum) {
        results(i.U) := scoreboards(i).read(addr)
      }
      results(warpID)
    }

    def readBypassed(warpID: UInt, addr: UInt): Bool = {
      val results = VecInit(Seq.fill(warpNum)(Wire(Bool())))
      for (i <- 0 until warpNum) {
        results(i.U) := scoreboards(i).readBypassed(addr)
      }
      results(warpID)
    }
  }

  class WarpRegFile(n: Int, w: Int, warpNum: Int, zero: Boolean = false) {
    val regFiles = Seq.fill(warpNum)(new RegFile(n, w, zero))

    def read(warpID: UInt, addr: UInt): UInt = {
      val results = VecInit(Seq.fill(warpNum)(Wire(UInt(w.W))))
      for (i <- 0 until warpNum) {
        results(i.U) := regFiles(i).read(addr)
      }
      results(warpID)
    }

    def write(warpID: UInt, addr: UInt, data: UInt): Unit = {
      for (i <- 0 until warpNum) {
        when(warpID === i.U) {
          regFiles(i).write(addr, data)
        }
      }
    }
  }
}
