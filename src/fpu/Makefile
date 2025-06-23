base_dir=$(abspath ../../)
vsrc_dir=$(abspath ../../depends/fpnew)

PREPROC_VERILOG = fpnew.preprocessed.sv

.PHONY: default $(PREPROC_VERILOG)
default: $(PREPROC_VERILOG)

UNAME_S := $(shell uname -s)

ifeq ($(UNAME_S),Darwin)
    PLATFORM := macos
    SED_CMD := sed -i''
else
    PLATFORM := linux
    SED_CMD := sed -i
endif

FPNEW_PKGS = \
    $(vsrc_dir)/src/common_cells/src/cf_math_pkg.sv \
    $(vsrc_dir)/src/common_cells/src/cb_filter_pkg.sv \
    $(vsrc_dir)/src/common_cells/src/ecc_pkg.sv \
	$(vsrc_dir)/src/fpu_div_sqrt_mvp/hdl/defs_div_sqrt_mvp.sv \
	$(vsrc_dir)/src/fpnew_pkg.sv

FPNEW_VSRCS = \
    $(vsrc_dir)/src/common_cells/include/common_cells/registers.svh \
    $(vsrc_dir)/src/common_cells/src/addr_decode.sv \
    $(vsrc_dir)/src/common_cells/src/binary_to_gray.sv \
    $(vsrc_dir)/src/common_cells/src/cb_filter.sv \
    $(vsrc_dir)/src/common_cells/src/cdc_2phase.sv \
    $(vsrc_dir)/src/common_cells/src/cdc_fifo_2phase.sv \
    $(vsrc_dir)/src/common_cells/src/cdc_fifo_gray.sv \
    $(vsrc_dir)/src/common_cells/src/clk_div.sv \
    $(vsrc_dir)/src/common_cells/src/counter.sv \
    $(vsrc_dir)/src/common_cells/src/delta_counter.sv \
    $(vsrc_dir)/src/common_cells/src/ecc_decode.sv \
    $(vsrc_dir)/src/common_cells/src/ecc_encode.sv \
    $(vsrc_dir)/src/common_cells/src/edge_detect.sv \
    $(vsrc_dir)/src/common_cells/src/edge_propagator.sv \
    $(vsrc_dir)/src/common_cells/src/edge_propagator_rx.sv \
    $(vsrc_dir)/src/common_cells/src/edge_propagator_tx.sv \
    $(vsrc_dir)/src/common_cells/src/exp_backoff.sv \
    $(vsrc_dir)/src/common_cells/src/fall_through_register.sv \
    $(vsrc_dir)/src/common_cells/src/fifo_v3.sv \
    $(vsrc_dir)/src/common_cells/src/gray_to_binary.sv \
    $(vsrc_dir)/src/common_cells/src/id_queue.sv \
    $(vsrc_dir)/src/common_cells/src/isochronous_spill_register.sv \
    $(vsrc_dir)/src/common_cells/src/lfsr.sv \
    $(vsrc_dir)/src/common_cells/src/lfsr_16bit.sv \
    $(vsrc_dir)/src/common_cells/src/lfsr_8bit.sv \
    $(vsrc_dir)/src/common_cells/src/lzc.sv \
    $(vsrc_dir)/src/common_cells/src/max_counter.sv \
    $(vsrc_dir)/src/common_cells/src/mv_filter.sv \
    $(vsrc_dir)/src/common_cells/src/onehot_to_bin.sv \
    $(vsrc_dir)/src/common_cells/src/plru_tree.sv \
    $(vsrc_dir)/src/common_cells/src/popcount.sv \
    $(vsrc_dir)/src/common_cells/src/rr_arb_tree.sv \
    $(vsrc_dir)/src/common_cells/src/rstgen.sv \
    $(vsrc_dir)/src/common_cells/src/rstgen_bypass.sv \
    $(vsrc_dir)/src/common_cells/src/serial_deglitch.sv \
    $(vsrc_dir)/src/common_cells/src/shift_reg.sv \
    $(vsrc_dir)/src/common_cells/src/spill_register.sv \
    $(vsrc_dir)/src/common_cells/src/stream_arbiter.sv \
    $(vsrc_dir)/src/common_cells/src/stream_arbiter_flushable.sv \
    $(vsrc_dir)/src/common_cells/src/stream_delay.sv \
    $(vsrc_dir)/src/common_cells/src/stream_demux.sv \
    $(vsrc_dir)/src/common_cells/src/stream_fifo.sv \
    $(vsrc_dir)/src/common_cells/src/stream_filter.sv \
    $(vsrc_dir)/src/common_cells/src/stream_fork.sv \
    $(vsrc_dir)/src/common_cells/src/stream_fork_dynamic.sv \
    $(vsrc_dir)/src/common_cells/src/stream_intf.sv \
    $(vsrc_dir)/src/common_cells/src/stream_join.sv \
    $(vsrc_dir)/src/common_cells/src/stream_mux.sv \
    $(vsrc_dir)/src/common_cells/src/stream_omega_net.sv \
    $(vsrc_dir)/src/common_cells/src/stream_register.sv \
    $(vsrc_dir)/src/common_cells/src/stream_to_mem.sv \
    $(vsrc_dir)/src/common_cells/src/stream_xbar.sv \
    $(vsrc_dir)/src/common_cells/src/sub_per_hash.sv \
    $(vsrc_dir)/src/common_cells/src/sync.sv \
    $(vsrc_dir)/src/common_cells/src/sync_wedge.sv \
    $(vsrc_dir)/src/common_cells/src/unread.sv \
    $(vsrc_dir)/src/fpu_div_sqrt_mvp/hdl/control_mvp.sv \
    $(vsrc_dir)/src/fpu_div_sqrt_mvp/hdl/div_sqrt_mvp_wrapper.sv \
    $(vsrc_dir)/src/fpu_div_sqrt_mvp/hdl/div_sqrt_top_mvp.sv \
    $(vsrc_dir)/src/fpu_div_sqrt_mvp/hdl/iteration_div_sqrt_mvp.sv \
    $(vsrc_dir)/src/fpu_div_sqrt_mvp/hdl/norm_div_sqrt_mvp.sv \
    $(vsrc_dir)/src/fpu_div_sqrt_mvp/hdl/nrbd_nrsc_mvp.sv \
    $(vsrc_dir)/src/fpu_div_sqrt_mvp/hdl/preprocess_mvp.sv \
    $(vsrc_dir)/vendor/opene906/E906_RTL_FACTORY/gen_rtl/clk/rtl/gated_clk_cell.v \
    $(vsrc_dir)/vendor/opene906/E906_RTL_FACTORY/gen_rtl/fdsu/rtl/pa_fdsu_ctrl.v \
    $(vsrc_dir)/vendor/opene906/E906_RTL_FACTORY/gen_rtl/fdsu/rtl/pa_fdsu_ff1.v \
    $(vsrc_dir)/vendor/opene906/E906_RTL_FACTORY/gen_rtl/fdsu/rtl/pa_fdsu_pack_single.v \
    $(vsrc_dir)/vendor/opene906/E906_RTL_FACTORY/gen_rtl/fdsu/rtl/pa_fdsu_prepare.v \
    $(vsrc_dir)/vendor/opene906/E906_RTL_FACTORY/gen_rtl/fdsu/rtl/pa_fdsu_round_single.v \
    $(vsrc_dir)/vendor/opene906/E906_RTL_FACTORY/gen_rtl/fdsu/rtl/pa_fdsu_special.v \
    $(vsrc_dir)/vendor/opene906/E906_RTL_FACTORY/gen_rtl/fdsu/rtl/pa_fdsu_srt_single.v \
    $(vsrc_dir)/vendor/opene906/E906_RTL_FACTORY/gen_rtl/fdsu/rtl/pa_fdsu_top.v \
    $(vsrc_dir)/vendor/opene906/E906_RTL_FACTORY/gen_rtl/fpu/rtl/pa_fpu_dp.v \
    $(vsrc_dir)/vendor/opene906/E906_RTL_FACTORY/gen_rtl/fpu/rtl/pa_fpu_frbus.v \
    $(vsrc_dir)/vendor/opene906/E906_RTL_FACTORY/gen_rtl/fpu/rtl/pa_fpu_src_type.v \
    $(vsrc_dir)/vendor/openc910/C910_RTL_FACTORY/gen_rtl/vfdsu/rtl/ct_vfdsu_ctrl.v \
    $(vsrc_dir)/vendor/openc910/C910_RTL_FACTORY/gen_rtl/vfdsu/rtl/ct_vfdsu_double.v \
    $(vsrc_dir)/vendor/openc910/C910_RTL_FACTORY/gen_rtl/vfdsu/rtl/ct_vfdsu_ff1.v \
    $(vsrc_dir)/vendor/openc910/C910_RTL_FACTORY/gen_rtl/vfdsu/rtl/ct_vfdsu_pack.v \
    $(vsrc_dir)/vendor/openc910/C910_RTL_FACTORY/gen_rtl/vfdsu/rtl/ct_vfdsu_prepare.v \
    $(vsrc_dir)/vendor/openc910/C910_RTL_FACTORY/gen_rtl/vfdsu/rtl/ct_vfdsu_round.v \
    $(vsrc_dir)/vendor/openc910/C910_RTL_FACTORY/gen_rtl/vfdsu/rtl/ct_vfdsu_scalar_dp.v \
    $(vsrc_dir)/vendor/openc910/C910_RTL_FACTORY/gen_rtl/vfdsu/rtl/ct_vfdsu_srt_radix16_bound_table.v \
    $(vsrc_dir)/vendor/openc910/C910_RTL_FACTORY/gen_rtl/vfdsu/rtl/ct_vfdsu_srt_radix16_with_sqrt.v \
    $(vsrc_dir)/vendor/openc910/C910_RTL_FACTORY/gen_rtl/vfdsu/rtl/ct_vfdsu_srt.v \
    $(vsrc_dir)/vendor/openc910/C910_RTL_FACTORY/gen_rtl/vfdsu/rtl/ct_vfdsu_top.v \
    $(vsrc_dir)/src/fpnew_cast_multi.sv \
    $(vsrc_dir)/src/fpnew_classifier.sv \
    $(vsrc_dir)/src/fpnew_divsqrt_multi.sv \
    $(vsrc_dir)/src/fpnew_divsqrt_th_32.sv \
    $(vsrc_dir)/src/fpnew_divsqrt_th_64_multi.sv \
    $(vsrc_dir)/src/fpnew_fma.sv \
    $(vsrc_dir)/src/fpnew_fma_multi.sv \
    $(vsrc_dir)/src/fpnew_noncomp.sv \
    $(vsrc_dir)/src/fpnew_opgroup_block.sv \
    $(vsrc_dir)/src/fpnew_opgroup_fmt_slice.sv \
    $(vsrc_dir)/src/fpnew_opgroup_multifmt_slice.sv \
    $(vsrc_dir)/src/fpnew_rounding.sv \
    $(vsrc_dir)/src/fpnew_top.sv

FPNEW_WRAPPER = \
	$(base_dir)/src/fpu/fpnew_wrapper.sv

ALL_VSRCS = $(FPNEW_PKGS) $(FPNEW_VSRCS) $(FPNEW_WRAPPER)

# lookup_dirs = $(shell find -L $(ibex_dir) -name target -prune -o -type d -print 2> /dev/null | grep '.*/\($(1)\)$$')
# INC_DIR_NAMES ?= vendor/lowrisc_ip/ip/prim/rtl formal/data_ind_timing vendor/lowrisc_ip/dv/sv/dv_utils
# INC_DIRS ?= $(foreach dir_name,$(INC_DIR_NAMES),$(call lookup_dirs,$(dir_name)))



$(PREPROC_VERILOG): $(ALL_VSRCS)
	cat  $(ALL_VSRCS)  > combined.sv
	$(SED_CMD) '/include \"common/d' combined.sv

clean:
	rm -rf combined.sv def.v undef.v
