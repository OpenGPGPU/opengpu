package ogpu.core

import chisel3._
import chisel3.experimental.SerializableModule

/** 数据管理Bundle
  *
  * 封装了寄存器文件和Scoreboard的所有信号
  */
class DataManagerBundle(parameter: OGPUParameter) extends Bundle {
  // 寄存器文件接口
  val regFile = new Bundle {
    val intRead = Flipped(new RegFileReadBundle(parameter.xLen, opNum = 3))
    val fpRead = Flipped(new RegFileReadBundle(parameter.xLen, opNum = 3))
    val vecRead = Flipped(new RegFileReadBundle(parameter.vLen, opNum = 2))
    val write = new RegFileWriteBundle(parameter)
  }

  // Scoreboard接口
  val scoreboard = new Bundle {
    val intRead =
      Flipped(
        new WarpScoreboardReadBundle(
          WarpScoreboardParameter(parameter.warpNum, 32, zero = true, opNum = 5)
        )
      )
    val fpRead =
      Flipped(
        new WarpScoreboardReadBundle(
          WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 4)
        )
      )

    val vecRead =
      Flipped(
        new WarpScoreboardReadBundle(
          WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 3)
        )
      )

    val intSet = new ScoreboardSetBundle(
      WarpScoreboardParameter(parameter.warpNum, 32, zero = true, opNum = 1)
    )
    val fpSet = new ScoreboardSetBundle(
      WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 4)
    )
    val vecSet = new ScoreboardSetBundle(
      WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 3)
    )
    val intClear = new ScoreboardClearBundle(
      WarpScoreboardParameter(parameter.warpNum, 32, zero = true, opNum = 5)
    )
    val fpClear = new ScoreboardClearBundle(
      WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 4)
    )
  }

  // 状态监控
  val status = new Bundle {
    val intRegBusy = UInt(32.W)
    val fpRegBusy = UInt(32.W)
    val vecRegBusy = UInt(32.W)
  }
}

/** 数据管理接口
  *
  * 封装了寄存器文件和Scoreboard的所有接口
  */
class DataManagerInterface(parameter: OGPUParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())

  // 寄存器文件接口
  val regFile = new Bundle {
    val intRead = new RegFileReadBundle(parameter.xLen, opNum = 3)
    val fpRead = new RegFileReadBundle(parameter.xLen, opNum = 3)
    val vecRead = new RegFileReadBundle(parameter.vLen, opNum = 2)
    val write = Input(new RegFileWriteBundle(parameter))
  }

  // Scoreboard接口
  val scoreboard = new Bundle {
    val intRead =
      new WarpScoreboardReadBundle(
        WarpScoreboardParameter(parameter.warpNum, 32, zero = true, opNum = 5)
      )

    val fpRead =
      new WarpScoreboardReadBundle(
        WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 4)
      )

    val vecRead =
      new WarpScoreboardReadBundle(
        WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 3)
      )

    val intSet = Input(
      new ScoreboardSetBundle(
        WarpScoreboardParameter(parameter.warpNum, 32, zero = true, opNum = 1)
      )
    )
    val fpSet = Input(
      new ScoreboardSetBundle(
        WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 4)
      )
    )
    val vecSet = Input(
      new ScoreboardSetBundle(
        WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 3)
      )
    )
    val intClear = Input(
      new ScoreboardClearBundle(
        WarpScoreboardParameter(parameter.warpNum, 32, zero = true, opNum = 5)
      )
    )
    val fpClear = Input(
      new ScoreboardClearBundle(
        WarpScoreboardParameter(parameter.warpNum, 32, zero = false, opNum = 4)
      )
    )
  }

  // 状态监控
  val status = Output(new Bundle {
    val intRegBusy = UInt(32.W)
    val fpRegBusy = UInt(32.W)
    val vecRegBusy = UInt(32.W)
  })
}

/** 数据管理模块
  *
  * 封装了寄存器文件和Scoreboard的管理
  */
class DataManager(val parameter: OGPUParameter)
    extends FixedIORawModule(new DataManagerInterface(parameter))
    with SerializableModule[OGPUParameter]
    with Public
    with ImplicitClock
    with ImplicitReset {

  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  // 寄存器文件实例化
  val intRegFile = Module(
    new WarpRegFile(
      WarpRegFileParameter(
        warpNum = parameter.warpNum,
        dataWidth = parameter.xLen,
        opNum = 3,
        zero = true
      )
    )
  )
  val fpRegFile = Module(
    new WarpRegFile(
      WarpRegFileParameter(
        warpNum = parameter.warpNum,
        dataWidth = parameter.xLen,
        opNum = 3,
        zero = false
      )
    )
  )
  val vecRegFile = Module(
    new WarpRegFile(
      WarpRegFileParameter(
        warpNum = parameter.warpNum,
        dataWidth = parameter.vLen,
        opNum = 2,
        zero = false
      )
    )
  )

  // Scoreboard实例化
  val intScoreboard = Module(
    new WarpScoreboard(
      WarpScoreboardParameter(
        warpNum = parameter.warpNum,
        regNum = 32,
        zero = true,
        opNum = 5
      )
    )
  )
  val fpScoreboard = Module(
    new WarpScoreboard(
      WarpScoreboardParameter(
        warpNum = parameter.warpNum,
        regNum = 32,
        zero = false,
        opNum = 4
      )
    )
  )
  val vecScoreboard = Module(
    new WarpScoreboard(
      WarpScoreboardParameter(
        warpNum = parameter.warpNum,
        regNum = 32,
        zero = false,
        opNum = 3
      )
    )
  )

  // 连接时钟和复位
  intRegFile.io.clock := io.clock
  intRegFile.io.reset := io.reset
  fpRegFile.io.clock := io.clock
  fpRegFile.io.reset := io.reset
  vecRegFile.io.clock := io.clock
  vecRegFile.io.reset := io.reset
  intScoreboard.io.clock := io.clock
  intScoreboard.io.reset := io.reset
  fpScoreboard.io.clock := io.clock
  fpScoreboard.io.reset := io.reset
  vecScoreboard.io.clock := io.clock
  vecScoreboard.io.reset := io.reset

  // 连接寄存器文件
  for (i <- 0 until 3) {
    intRegFile.io.read(i).addr := io.regFile.intRead.read(i).addr
    intRegFile.io.read(i).warpID := 0.U // 暂时使用默认warpID
  }
  io.regFile.intRead.readData := intRegFile.io.readData

  for (i <- 0 until 3) {
    fpRegFile.io.read(i).addr := io.regFile.fpRead.read(i).addr
    fpRegFile.io.read(i).warpID := 0.U // 暂时使用默认warpID
  }
  io.regFile.fpRead.readData := fpRegFile.io.readData

  for (i <- 0 until 2) {
    vecRegFile.io.read(i).addr := io.regFile.vecRead.read(i).addr
    vecRegFile.io.read(i).warpID := 0.U // 暂时使用默认warpID
  }
  io.regFile.vecRead.readData := vecRegFile.io.readData
  intRegFile.io.write <> io.regFile.write
  fpRegFile.io.write <> io.regFile.write

  // 连接vecRegFile的write接口
  vecRegFile.io.write.en := false.B
  vecRegFile.io.write.warpID := 0.U
  vecRegFile.io.write.addr := 0.U
  vecRegFile.io.write.data := 0.U

  // 连接Scoreboard
  intScoreboard.io.read <> io.scoreboard.intRead
  fpScoreboard.io.read <> io.scoreboard.fpRead
  vecScoreboard.io.read <> io.scoreboard.vecRead
  intScoreboard.io.set <> io.scoreboard.intSet
  fpScoreboard.io.set <> io.scoreboard.fpSet
  vecScoreboard.io.set <> io.scoreboard.vecSet
  intScoreboard.io.clear <> io.scoreboard.intClear
  fpScoreboard.io.clear <> io.scoreboard.fpClear

  // 连接vecScoreboard的clear接口
  vecScoreboard.io.clear.en := false.B
  vecScoreboard.io.clear.warpID := 0.U
  vecScoreboard.io.clear.addr := 0.U

  // 状态监控
  io.status.intRegBusy := intScoreboard.io.read.busy.asUInt
  io.status.fpRegBusy := fpScoreboard.io.read.busy.asUInt
  io.status.vecRegBusy := vecScoreboard.io.read.busy.asUInt
}
