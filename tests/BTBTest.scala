import chisel3._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.VCDHackedEphemeralSimulator._
import ogpu.core._

// class BTBTest extends AnyFlatSpec {
//   val param = BTBParameter(
//     useAsyncReset = false,
//     fetchBytes = 4,
//     vaddrBits = 32,
//     entries = 32,
//     nMatchBits = 14,
//     nPages = 8,
//     nRAS = 8,
//     cacheBlockBytes = 64,
//     iCacheSet = 64,
//     useCompressed = false,
//     updatesOutOfOrder = false,
//     fetchWidth = 4,
//     bhtParameter = Some(
//       BHTParameter(
//         nEntries = 512,
//         counterLength = 2,
//         historyLength = 8,
//         historyBits = 3
//       )
//     )
//   )
// 
//   behavior.of("BTB")
// 
//   it should "handle basic branch prediction correctly" in {
//     simulate(new BTB(param), "btbtest1") { dut =>
//       // Initialize
//       dut.io.clock.step()
//       dut.io.reset.poke(true.B)
//       dut.io.clock.step()
//       dut.io.reset.poke(false.B)
//       dut.io.flush.poke(false.B)
// 
//       // Initial state - no prediction
//       dut.io.req.valid.poke(true.B)
//       dut.io.req.bits.addr.poke(0x1000.U)
//       dut.io.clock.step()
//       dut.io.resp.valid.expect(false.B)
//       dut.io.req.valid.poke(false.B)
// 
//       // Update BTB with a branch
//       dut.io.btb_update.valid.poke(true.B)
//       dut.io.btb_update.bits.pc.poke(0x1000.U)
//       dut.io.btb_update.bits.target.poke(0x3000.U)
//       dut.io.btb_update.bits.taken.poke(true.B)
//       dut.io.btb_update.bits.isValid.poke(true.B)
//       dut.io.btb_update.bits.cfiType.poke(CFIType.branch)
//       dut.io.clock.step()
//       dut.io.btb_update.valid.poke(false.B)
//       dut.io.clock.step(5)
// 
//       // Check prediction
//       dut.io.req.valid.poke(true.B)
//       dut.io.req.bits.addr.poke(0x1000.U)
//       dut.io.clock.step()
//       dut.io.resp.valid.expect(true.B)
//       dut.io.resp.bits.target.expect(0x3000.U)
//     }
//   }
// 
//   it should "handle RAS operations correctly" in {
//     simulate(new BTB(param), "btbtest2") { dut =>
//       // Initialize
//       dut.io.clock.step()
//       dut.io.reset.poke(true.B)
//       dut.io.clock.step()
//       dut.io.reset.poke(false.B)
// 
//       // Add call instruction to BTB
//       dut.io.btb_update.valid.poke(true.B)
//       dut.io.btb_update.bits.pc.poke(0x1000.U)
//       dut.io.btb_update.bits.target.poke(0x2000.U)
//       dut.io.btb_update.bits.isValid.poke(true.B)
//       dut.io.btb_update.bits.cfiType.poke(CFIType.call)
//       dut.io.clock.step()
// 
//       // Update RAS with return address
//       dut.io.ras_update.valid.poke(true.B)
//       dut.io.ras_update.bits.cfiType.poke(CFIType.call)
//       dut.io.ras_update.bits.returnAddr.poke(0x1004.U)
//       dut.io.clock.step()
//       dut.io.ras_update.valid.poke(false.B)
// 
//       // Add return instruction to BTB
//       dut.io.btb_update.valid.poke(true.B)
//       dut.io.btb_update.bits.pc.poke(0x2004.U)
//       dut.io.btb_update.bits.isValid.poke(true.B)
//       dut.io.btb_update.bits.cfiType.poke(CFIType.ret)
//       dut.io.clock.step()
//       dut.io.btb_update.valid.poke(false.B)
// 
//       // Check return prediction
//       dut.io.req.valid.poke(true.B)
//       dut.io.req.bits.addr.poke(0x2004.U)
//       dut.io.clock.step()
//       dut.io.resp.valid.expect(true.B)
//       dut.io.resp.bits.target.expect(0x1004.U)
//     }
//   }
// 
//   it should "handle BHT updates correctly" in {
//     simulate(new BTB(param), "btbtest3") { dut =>
//       // Initialize
//       dut.io.clock.step()
//       dut.io.reset.poke(true.B)
//       dut.io.clock.step()
//       dut.io.reset.poke(false.B)
// 
//       // Add branch to BTB
//       dut.io.btb_update.valid.poke(true.B)
//       dut.io.btb_update.bits.pc.poke(0x1000.U)
//       dut.io.btb_update.bits.target.poke(0x2000.U)
//       dut.io.btb_update.bits.isValid.poke(true.B)
//       dut.io.btb_update.bits.cfiType.poke(CFIType.branch)
//       dut.io.clock.step()
//       dut.io.btb_update.valid.poke(false.B)
// 
//       // Update BHT with taken branch
//       dut.io.bht_update.valid.poke(true.B)
//       dut.io.bht_update.bits.pc.poke(0x1000.U)
//       dut.io.bht_update.bits.taken.poke(true.B)
//       dut.io.bht_update.bits.mispredict.poke(false.B)
//       dut.io.bht_update.bits.branch.poke(true.B)
//       dut.io.clock.step()
//       dut.io.bht_update.valid.poke(false.B)
// 
//       // Check prediction includes BHT info
//       dut.io.req.valid.poke(true.B)
//       dut.io.req.bits.addr.poke(0x1000.U)
//       dut.io.clock.step()
//       dut.io.resp.valid.expect(true.B)
//     }
//   }
// }
