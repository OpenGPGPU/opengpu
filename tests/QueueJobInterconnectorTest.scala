package ogpu.dispatcher

import chisel3._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.VCDHackedEphemeralSimulator._

class QueueJobInterconnectorTest extends AnyFlatSpec {
  val param = QueueJobParameter(
    useAsyncReset = false,
    clockGate = false,
    numQueuePorts = 3, // More queue ports than job ports
    numJobPorts = 2
  )

  behavior.of("QueueJobInterconnector")

  it should "route single queue request to job port correctly" in {
    simulate(new QueueJobInterconnector(param), "queuejobsingle") { dut =>
      // Initialize
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)
      dut.io.job(0).ready.poke(true.B)
      dut.io.job(1).ready.poke(true.B)
      dut.io.clock.step()

      // Test initial state - all ports should be ready
      dut.io.queue(0).ready.expect(true.B)
      dut.io.queue(1).ready.expect(true.B)
      dut.io.queue(2).ready.expect(true.B)

      // Send request from queue 0
      dut.io.queue(0).valid.poke(true.B)
      dut.io.queue(0).bits.grid_size_x.poke(2.U)
      dut.io.queue(0).bits.workgroup_size_x.poke(32.U)

      // Verify routing
      dut.io.job(0).valid.expect(true.B)
      dut.io.job(0).bits.grid_size_x.expect(2.U)
      dut.io.job(0).bits.workgroup_size_x.expect(32.U)

      // Send response back
      dut.io.job_resp(0).valid.poke(true.B)
      dut.io.queue_resp(0).ready.poke(true.B)
      dut.io.clock.step()

      // Verify response routing
      dut.io.queue_resp(0).valid.expect(true.B)
      dut.io.clock.step(5)
    }
  }

  it should "handle multiple concurrent requests" in {
    simulate(new QueueJobInterconnector(param), "queuejobmultiple") { dut =>
      // Reset
      dut.io.clock.step()
      dut.io.reset.poke(true.B)
      dut.io.clock.step()
      dut.io.reset.poke(false.B)

      // Send requests from both queues
      dut.io.queue(0).valid.poke(true.B)
      dut.io.queue(0).bits.grid_size_x.poke(2.U)
      dut.io.queue(1).bits.grid_size_x.poke(3.U)

      // Both job ports ready
      dut.io.job(0).ready.poke(true.B)
      dut.io.job(1).ready.poke(true.B)

      dut.io.job(0).valid.expect(true.B)
      dut.io.clock.step()
      // Verify both routed correctly
      dut.io.queue(1).valid.poke(true.B)
      dut.io.queue(0).valid.poke(false.B)
      dut.io.job(1).valid.expect(true.B)
      dut.io.clock.step()
      dut.io.queue(0).valid.poke(false.B)
      dut.io.queue(1).valid.poke(false.B)
      dut.io.queue(2).valid.poke(true.B)
      dut.io.clock.step(5)

      // Send responses
      dut.io.job_resp(0).valid.poke(true.B)
      dut.io.job_resp(1).valid.poke(true.B)
      dut.io.queue_resp(0).ready.poke(true.B)
      dut.io.queue_resp(1).ready.poke(true.B)
      dut.io.queue_resp(2).ready.poke(true.B)

      // Verify responses routed back
      dut.io.queue_resp(1).valid.expect(true.B)
      dut.io.queue_resp(0).valid.expect(true.B)
      dut.io.clock.step(5)
    }
  }
}
