module fpnew_wrapper #(
  parameter WIDTH = 32,
  parameter NUM_OPERANDS = 3
)(
  input  logic                               clk_i,
  input  logic                               rst_ni,
  input  logic [NUM_OPERANDS*WIDTH-1:0]      operands_i_flat,
  input  logic [2:0]                         rnd_mode_i,
  input  logic [4:0]                         op_i,
  input  logic                               op_mod_i,
  input  logic [1:0]                         src_fmt_i,
  input  logic [1:0]                         dst_fmt_i,
  input  logic [1:0]                         int_fmt_i,
  input  logic                               vectorial_op_i,
  input  logic                               tag_i,
  input  logic                               in_valid_i,
  output logic                               in_ready_o,
  input  logic                               flush_i,
  output logic [WIDTH-1:0]                   result_o,
  output logic [4:0]                         status_o,
  output logic                               tag_o,
  output logic                               out_valid_o,
  input  logic                               out_ready_i,
  output logic                               busy_o
);

  // Unflatten operands for fpnew_top
  logic [NUM_OPERANDS-1:0][WIDTH-1:0] operands_i;
  genvar i;
  generate
    for (i = 0; i < NUM_OPERANDS; i++) begin
      assign operands_i[i] = operands_i_flat[(i+1)*WIDTH-1:i*WIDTH];
    end
  endgenerate


  // Features parameter for fpnew_top
  localparam fpnew_pkg::fpu_features_t FP_FEATURE = WIDTH == 64 ? fpnew_pkg::RV64D_Xsflt : fpnew_pkg::RV32F_Xsflt;

  localparam int unsigned NumLanes = fpnew_pkg::max_num_lanes(FP_FEATURE.Width, FP_FEATURE.FpFmtMask, FP_FEATURE.EnableVectors);

  // SIMD mask: 全1（不使用SIMD时）
  logic [NumLanes-1:0] simd_mask_i;
  assign simd_mask_i = '0;

  fpnew_top #(
    .Features      (FP_FEATURE)
  ) fpnew_inst (
    .clk_i           (clk_i),
    .rst_ni          (rst_ni),
    .operands_i      (operands_i),
    .rnd_mode_i      (fpnew_pkg::roundmode_e'(rnd_mode_i)),
    .op_i            (fpnew_pkg::operation_e'(op_i)),
    .op_mod_i        (op_mod_i),
    .src_fmt_i       (fpnew_pkg::fp_format_e'(src_fmt_i)),
    .dst_fmt_i       (fpnew_pkg::fp_format_e'(dst_fmt_i)),
    .int_fmt_i       (fpnew_pkg::int_format_e'(int_fmt_i)),
    .vectorial_op_i  (vectorial_op_i),
    .tag_i           (tag_i),
    .simd_mask_i     (simd_mask_i),
    .in_valid_i      (in_valid_i),
    .in_ready_o      (in_ready_o),
    .flush_i         (flush_i),
    .result_o        (result_o),
    .status_o        (status_o),
    .tag_o           (tag_o),
    .out_valid_o     (out_valid_o),
    .out_ready_i     (out_ready_i),
    .busy_o          (busy_o)
  );

endmodule
