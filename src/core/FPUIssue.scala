package ogpu.core

import chisel3._
import chisel3.util._

class FpuIssueIO(parameter: OGPUParameter) extends Bundle {
  val in = Flipped(DecoupledIO(new Bundle {
    val instruction = new InstructionBundle(parameter.warpNum, 32)
    val fpuResult = new FPUDecoderInterface(parameter)
  }))
  val fpRegFile = Flipped(new RegFileReadBundle(parameter.xLen, opNum = 3))
  val intRegFile = Flipped(new RegFileReadBundle(parameter.xLen, opNum = 1))
  val fpScoreboard = Flipped(
    new WarpScoreboardReadBundle(
      WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 4)
    )
  )
  val intScoreboard = Flipped(
    new WarpScoreboardReadBundle(
      WarpScoreboardParameter(parameter.warpNum, 32, zero = true, opNum = 2)
    )
  )
  val fpuIssue = DecoupledIO(new FPUOperandBundle(parameter))
}

class FpuIssue(val parameter: OGPUParameter) extends Module {
  val io = IO(new FpuIssueIO(parameter))

  val inst = io.in.bits.instruction.instruction
  val rs1 = inst(19, 15)
  val rs2 = inst(24, 20)
  val rs3 = inst(31, 27)
  val rd = inst(11, 7)
  val fpuDecode = io.in.bits.fpuResult.output

  // 指令类型判断
  val funct7 = inst(31, 25)
  val funct3 = inst(14, 12)

  // 判断是否为浮点-整数转换指令
  val isFmvXW = (funct7 === "b1110000".U) && (funct3 === "b000".U) // fmv.x.w
  val isFmvWX = (funct7 === "b1111000".U) && (funct3 === "b000".U) // fmv.w.x
  val isFcvtToInt = (funct7 === "b1100000".U) && (funct3(1, 0) === "b00".U) // fcvt.w.s, fcvt.w.d, fcvt.l.s, fcvt.l.d
  val isFcvtToFloat = (funct7 === "b1101000".U) && (funct3(1, 0) === "b00".U) // fcvt.s.w, fcvt.d.w, fcvt.s.l, fcvt.d.l

  // 判断寄存器类型
  val rs1IsInt = isFmvWX || isFcvtToFloat // 这些指令的rs1是整数寄存器
  val rs2IsInt = false.B // FPU指令的rs2通常是浮点寄存器
  val rs3IsInt = false.B // FPU指令的rs3通常是浮点寄存器
  val rdIsInt = isFmvXW || isFcvtToInt // 这些指令的rd是整数寄存器

  // 控制信号：是否使用寄存器
  val useRs1 = true.B // FPU 指令通常都使用 rs1
  val useRs2 = true.B // FPU 指令通常都使用 rs2
  val useRs3 = fpuDecode(parameter.fren3)
  val useRd = fpuDecode(parameter.fwen)

  // 寄存器读 - 根据指令类型选择寄存器文件
  io.fpRegFile.read(0).addr := rs1
  io.fpRegFile.read(1).addr := rs2
  io.fpRegFile.read(2).addr := rs3
  io.intRegFile.read(0).addr := rs1 // 整数寄存器只需要读rs1

  // Scoreboard 读 - 根据寄存器类型选择scoreboard
  io.fpScoreboard.warpID := io.in.bits.instruction.wid
  io.fpScoreboard.addr(0) := rs1
  io.fpScoreboard.addr(1) := rs2
  io.fpScoreboard.addr(2) := rs3
  io.fpScoreboard.addr(3) := rd

  io.intScoreboard.warpID := io.in.bits.instruction.wid
  io.intScoreboard.addr(0) := rs1 // 整数寄存器读rs1
  io.intScoreboard.addr(1) := rd // 整数寄存器写rd

  // busy 检查：根据寄存器类型选择对应的scoreboard
  val rs1Busy = useRs1 && Mux(
    rs1IsInt,
    io.intScoreboard.busy.lift(0).getOrElse(false.B),
    io.fpScoreboard.busy.lift(0).getOrElse(false.B)
  )
  val rs2Busy = useRs2 && io.fpScoreboard.busy.lift(1).getOrElse(false.B) // rs2总是浮点寄存器
  val rs3Busy = useRs3 && io.fpScoreboard.busy.lift(2).getOrElse(false.B) // rs3总是浮点寄存器
  val rdBusy = useRd && Mux(
    rdIsInt,
    io.intScoreboard.busy.lift(1).getOrElse(false.B), // 检查整数rd的busy状态
    io.fpScoreboard.busy.lift(3).getOrElse(false.B)
  ) && (rd =/= 0.U)

  val anyRegBusy = rs1Busy || rs2Busy || rs3Busy
  val canIssue = !anyRegBusy && !rdBusy

  // 选择寄存器数据 - 根据指令类型选择数据来源
  val rs1Data = Mux(rs1IsInt, io.intRegFile.readData(0), io.fpRegFile.readData(0))
  val rs2Data = io.fpRegFile.readData(1) // rs2总是浮点寄存器
  val rs3Data = io.fpRegFile.readData(2) // rs3总是浮点寄存器

  // FPU 发射
  io.fpuIssue.valid := canIssue && io.in.valid
  io.fpuIssue.bits.rs1Data := rs1Data
  io.fpuIssue.bits.rs2Data := rs2Data
  io.fpuIssue.bits.rs3Data := rs3Data
  io.fpuIssue.bits.rnd_mode := 0.U // TODO: 需要根据实际情况赋值
  io.fpuIssue.bits.op := fpuDecode(parameter.op)
  io.fpuIssue.bits.op_mod := fpuDecode(parameter.op_mod)
  io.fpuIssue.bits.src_fmt := fpuDecode(parameter.src_fmt)
  io.fpuIssue.bits.dst_fmt := fpuDecode(parameter.dst_fmt)
  io.fpuIssue.bits.int_fmt := fpuDecode(parameter.int_fmt)
  io.fpuIssue.bits.vectorial_op := fpuDecode(parameter.vectorial_op)
  io.fpuIssue.bits.tag_i := fpuDecode(parameter.tag_i)
  io.fpuIssue.bits.flush := false.B
  io.fpuIssue.bits.warpID := io.in.bits.instruction.wid
  io.fpuIssue.bits.rd := rd
  io.fpuIssue.bits.pc := io.in.bits.instruction.pc

  // ready 信号
  io.in.ready := io.fpuIssue.ready
}
