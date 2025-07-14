package ogpu.core

import chisel3._
import chisel3.util._

class SimpleFIFOQueue[T <: Data](gen: T, entries: Int) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(gen))
    val deq = Decoupled(gen)
  })

  val ram = Reg(Vec(entries, gen))
  val head = RegInit(0.U(log2Ceil(entries).W))
  val tail = RegInit(0.U(log2Ceil(entries).W))
  val maybe_full = RegInit(false.B)

  val ptr_match = head === tail
  val empty = ptr_match && !maybe_full
  val full = ptr_match && maybe_full

  io.enq.ready := !full
  io.deq.valid := !empty
  io.deq.bits := ram(head)

  when(io.enq.fire) {
    ram(tail) := io.enq.bits
    tail := tail + 1.U
    when(ptr_match && !maybe_full) { maybe_full := true.B }
  }
  when(io.deq.fire) {
    head := head + 1.U
    when(ptr_match && maybe_full) { maybe_full := false.B }
  }
}
