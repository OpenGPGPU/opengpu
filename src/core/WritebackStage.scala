package ogpu.core

import chisel3._
import chisel3.util._

class WritebackStageIO(parameter: OGPUParameter) extends Bundle {
  val in = Flipped(DecoupledIO(new ResultBundle(parameter)))
  val regFileWrite = Output(new RegFileWriteBundle(parameter))
  val clearScoreboard = Output(new ScoreboardClearBundle(WarpScoreboardParameter(parameter.warpNum, 32)))
}

class WritebackStage(parameter: OGPUParameter) extends Module {
  val io = IO(new WritebackStageIO(parameter))

  // Writeback logic
  io.regFileWrite.en := false.B
  io.regFileWrite.warpID := 0.U
  io.regFileWrite.addr := 0.U
  io.regFileWrite.data := 0.U
  io.clearScoreboard.en := false.B
  io.clearScoreboard.warpID := 0.U
  io.clearScoreboard.addr := 0.U

  when(io.in.valid) {
    // Update register file
    io.regFileWrite.en := true.B
    io.regFileWrite.warpID := io.in.bits.warpID
    io.regFileWrite.addr := io.in.bits.rd
    io.regFileWrite.data := io.in.bits.result

    // Clear scoreboard
    io.clearScoreboard.en := true.B
    io.clearScoreboard.warpID := io.in.bits.warpID
    io.clearScoreboard.addr := io.in.bits.rd
  }

  io.in.ready := true.B
}
