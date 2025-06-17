module fpnew_wrapper #(
  parameter WIDTH = 32,
  parameter VECTOR = 0,
  parameter NANBOX = 1,
  parameter NUM_OPERANDS = 3,
  parameter FP_FMT_MASK = 5'b10001,
  parameter INT_FMT_MASK = 4'b0110
)(
  input  logic                               clk_i,
  input  logic                               rst_ni,
  // Input signals
  input  logic [NUM_OPERANDS*WIDTH-1:0]      operands_i_flat,
  input  logic [2:0]                         rnd_mode_i,
  input  logic [4:0]                         op_i,
  input  logic                               op_mod_i,
  input  logic [1:0]                         src_fmt_i,
  input  logic [1:0]                         dst_fmt_i,
  input  logic [1:0]                         int_fmt_i,
  input  logic                               vectorial_op_i,
  input  logic [4:0]                         tag_i,
  // Input Handshake
  input  logic                               in_valid_i,
  output logic                               in_ready_o,
  input  logic                               flush_i,
  // Output signals
  output logic [WIDTH-1:0]                   result_o,
  output logic [4:0]                         status_o,
  output logic [4:0]                         tag_o,
  // Output handshake
  output logic                               out_valid_o,
  input  logic                               out_ready_i,
  // Indication of valid data in flight
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

  fpnew_top #(
    .Features      (FP_FEATURE),
    .Implementation(fpnew_pkg::DEFAULT_NOREGS),
    .TagType       (logic [4:0]),
    .WIDTH         (WIDTH),
    .NUM_OPERANDS  (NUM_OPERANDS)
  ) fpnew_inst (
    .clk_i           (clk_i),
    .rst_ni          (rst_ni),
    .operands_i      (operands_i),
    .rnd_mode_i      (rnd_mode_i),
    .op_i            (op_i),
    .op_mod_i        (op_mod_i),
    .src_fmt_i       (src_fmt_i),
    .dst_fmt_i       (dst_fmt_i),
    .int_fmt_i       (int_fmt_i),
    .vectorial_op_i  (vectorial_op_i),
    .tag_i           (tag_i),
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
