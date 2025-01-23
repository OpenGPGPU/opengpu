package workgroup.dispatcher

import chisel3._
import chisel3.iotesters.{Driver, PeekPokeTester}
import chisel3.util._
import org.scalatest.{FlatSpec, Matchers}

class WorkGroupDispatcherSpec extends FlatSpec with Matchers {

  behavior.of("WorkGroupDispatcher")

  it should "correctly break down workgroups into warp tasks" in {
    // Create a test instance of the WorkGroupDispatcher
    val parameter = DispatcherParameter(useAsyncReset = true, clockGate = false, bufferNum = 4)
    Driver.execute(Array("--generate-vcd-output", "on"), () => new WorkGroupDispatcher(parameter)) { c =>
      new PeekPokeTester(c) {
        // Initialize inputs and test the functionality
        poke(c.io.aql.valid, 1)
        poke(c.io.aql.bits.grid_size_x, 16)
        poke(c.io.aql.bits.grid_size_y, 16)
        step(1)

        // Check the state transitions and outputs
        expect(c.state, c.State.Working)
        // Add more assertions based on expected behavior
      }
    } should be(true)
  }

  it should "handle reset correctly" in {
    val parameter = DispatcherParameter(useAsyncReset = true, clockGate = false, bufferNum = 4)
    Driver.execute(Array("--generate-vcd-output", "on"), () => new WorkGroupDispatcher(parameter)) { c =>
      new PeekPokeTester(c) {
        poke(c.io.reset, 1)
        step(1)
        expect(c.state, c.State.Idle)
      }
    } should be(true)
  }
}
