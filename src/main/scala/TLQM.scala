package ogpu.scheduler

import chisel3._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.regmapper._
import chisel3.util.{is, switch, Enum}

case class QMParams(baseAddress: BigInt = 0x03000000) {
  def address = AddressSet(baseAddress, 0xff)
}

class TLQM(
  params: QMParams
)(
  implicit p: Parameters)
    extends LazyModule {

  val device = new SimpleDevice("qm", Seq("zhangjiang, qm")) {
    override val alwaysExtended = true
  }

  val node = TLRegisterNode(address = Seq(params.address), device = device, beatBytes = 8)

  val clientParameters = TLMasterPortParameters.v1(
    clients = Seq(
      TLMasterParameters.v1(
        "tlqm master",
        sourceId = IdRange(0, 16)
      )
    )
  )
  val clientNode = TLClientNode(Seq(clientParameters))

  lazy val module = new Impl(this)
  class Impl(
    outer: TLQM
  )(
    implicit p: Parameters)
      extends LazyModuleImp(outer) {
    val io = IO(new Bundle {
      // val tlb = new TlbRequestIO(1)
    })

    val (tl_out, edge_out) = outer.clientNode.out(0)
    val base_addr = RegInit(0.U(64.W))
    val rptr = RegInit(0.U(64.W))
    val wptr = RegInit(0.U(64.W))
    val size = RegInit(0.U(64.W))
    val enable = RegInit(0.B)
    val data = RegInit(0.U(512.W))

    val pending = WireInit(rptr =/= wptr)

    // step1 issue tlb request, update rptr
    val s1_idle :: s1_req :: s1_ack :: Nil = Enum(3)

    val s1_state = RegInit(s1_idle)
    val s1_rptr = RegInit(0.U(64.W))

  }
}
