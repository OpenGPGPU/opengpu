# PTW (Page Table Walker) Module

## Overview

The PTW module implements the page table walker functionality for the OGPU core, following the RISC-V privilege specification. It handles virtual-to-physical address translation by walking through the page table hierarchy.

## Interface

### TLB Interface
- `tlb.req`: Receives page table walk requests from TLB
- `tlb.resp`: Sends page table walk responses back to TLB
- `tlb.ptbr`: Page table base register from CSR
- `tlb.status`: Machine status register for privilege level

### Memory Interface
- `mem`: Cache interface for page table entry access
  - Uses cache memory request protocol
  - Supports 8-byte PTE reads through GPUCache
  - Handles memory errors through cache exception signal

## State Machine

The PTW implements a 4-state state machine:

1. **s_idle**: Waiting for TLB request
2. **s_request**: Calculating page table address and issuing memory request
3. **s_wait**: Waiting for memory response
4. **s_response**: Preparing response for TLB

## Page Table Walk Process

1. **Request Reception**: PTW receives VPN and stage2 flag from TLB
2. **Address Calculation**: Calculates page table entry address based on:
   - PTBR base address
   - Current page table level
   - VPN index for current level
3. **Memory Access**: Issues cache memory request for PTE through GPUCache
4. **PTE Processing**: Parses PTE from cache response and determines next action:
   - If PTE is valid and points to next level: continue walk
   - If PTE is valid leaf: complete walk
   - If PTE is invalid: generate page fault
5. **Response Generation**: Sends response to TLB with PTE and status

## PTE Format

The PTW handles RISC-V PTE format:
- V (Valid): 1 bit
- R (Readable): 1 bit
- W (Writable): 1 bit
- X (Executable): 1 bit
- U (User): 1 bit
- G (Global): 1 bit
- A (Accessed): 1 bit
- D (Dirty): 1 bit
- PPN (Physical Page Number): 44 bits
- Reserved fields: 10 bits

## Error Handling

The PTW generates various error conditions:
- **Page Fault (pf)**: Invalid PTE or insufficient permissions
- **Access Exception (ae_ptw)**: Memory access error during walk
- **Final Access Exception (ae_final)**: Final access check failure

## Configuration

The PTW supports various configuration options:
- Page table levels (2-4 for RV32, 3-4 for RV64)
- Address widths (32/64 bit)
- Virtual memory enable/disable

## Usage

The PTW module is instantiated by the TLB and handles all page table walks transparently. It follows the same parameter structure as TLB for consistency. The PTW accesses memory through the GPUCache, which provides a unified memory interface for both normal cache operations and PTW requests.

## Testing

The module includes comprehensive tests covering:
- Basic page table walks
- Invalid PTE handling
- Error condition generation
- State machine transitions 