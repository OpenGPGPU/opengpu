package ogpu.core

import chisel3._
import chisel3.util._

/** 流水线控制器
  *
  * 管理多个流水线阶段之间的协调
  */
class PipelineController(val parameter: OGPUParameter) extends Module {
  val io = IO(new Bundle {
    val stages = Vec(
      5,
      new Bundle {
        val valid = Output(Bool())
        val ready = Input(Bool())
        val flush = Output(Bool())
        val stall = Output(Bool())
      }
    )
    val globalFlush = Input(Bool())
    val globalStall = Input(Bool())
  })

  // 流水线控制逻辑
  for (i <- 0 until 5) {
    io.stages(i).valid := true.B
    io.stages(i).flush := io.globalFlush
    io.stages(i).stall := io.globalStall || !io.stages(i).ready
  }
}

/** 简单的流水线阶段基类
  *
  * 提供基本的流水线控制功能
  */
abstract class SimplePipelineStage(val parameter: OGPUParameter) extends Module {
  val io = IO(new Bundle {
    val flush = Input(Bool())
    val stall = Input(Bool())
  })

  // 子类需要实现的具体逻辑
  protected def process(): Unit
}

/** 流水线阶段接口
  *
  * 定义标准的流水线阶段接口
  */
class PipelineStageInterface[T <: Data](dataType: T) extends Bundle {
  val in = Flipped(DecoupledIO(dataType))
  val out = DecoupledIO(dataType)
  val flush = Input(Bool())
  val stall = Input(Bool())
}
