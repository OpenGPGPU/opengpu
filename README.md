# OPENGPU

The goal of this project is to develop a simple GPU.

## Project Description
A project to create a simplified GPU implementation with chisel.

```mermaid
%%{
  init: {
    'theme': 'dark',
    'themeVariables': {
      'lineColor': 'rgb(235, 160, 172)',
      'clusterBkg': 'rgba(33,33,33,0.5)',
      'fontFamily': 'Inter',
      'fontSize': '14px'
    }
  }
}%%

flowchart TD
  subgraph sqs[SoftwareQueues]
    sq1(SoftwareQueue)
    sqn(SoftwareQueue)
  end

  subgraph qs[QueueScheduler]
    style qs stroke-dasharray: 5 5
  end

  subgraph hqs[HardwareQueues]
    hq1(HardwareQueue)
    hqn(HardwareQueue)
  end

  subgraph jd[JobDispatchers]
    jd1(JobDispatcher)
    jdn(JObDispatcher)
  end

  subgraph wgd[WorkGroupDispatchers]
    wgd1(WorkGroupDispatcher)
    wgdn(WorkGroupDispatcher)
  end

  subgraph cus[ComputeUnits]
    subgraph cu1[ComputeUnit]
      ws(WarpScheduler)
      fetch(InstFetch)
      icache(Icache)
      issue(Issue)
      exe(Execution)
      mem(MemoryAccess)
      wb(WriteBack)
    end
    subgraph cun[ComputeUnit]
      wsn(WarpScheduler)
      fetchn(InstFetch)
      icachen(Icache)
      issuen(Issue)
      exen(Execution)
      memn(MemoryAccess)
      wbn(WriteBack)
    end
  end

  sq1 --> qs
  sqn --> qs
  qs --> hq1
  qs --> hqn
  hq1 --> jd
  hqn --> jd

  jd1 --> wgd
  jdn --> wgdn

  wgd1 --> cus
  wgdn --> cus

```
## Current Status and Future Work

### Current Status
- Core Architecture : The project is building a GPU core based on the RISC-V Vector (RVV) extension. It includes fundamental execution units like Instruction Issue (Issue Stage), ALU, and FPU, along with parameter configurations for the Vector Register File (VRF) and VectorCore.
- Instruction Set Support : Currently focuses on the RISC-V Vector (RVV) extension instructions.
- Modular Design : The code structure demonstrates a modular approach with subdirectories such as core , dispatcher , fpu , and vector .

### Future Work
To achieve a complete GPU functionality, beyond the existing foundation, the following aspects typically need to be considered and developed:

1. More Comprehensive Instruction Set Support : Besides RVV, GPUs usually have specialized instruction sets for graphics rendering (e.g., texture sampling, pixel operations) and general-purpose computing (e.g., atomic operations, synchronization instructions).
2. Graphics Rendering Pipeline :
   - Vertex Processing : Vertex shaders, geometry shaders.
   - Rasterization : Converting geometric primitives into pixels.
   - Fragment Processing : Fragment shaders, texture units, depth testing, stencil testing, blending.
   - Output Merger : Writing processed fragments to the framebuffer.
3. General-Purpose Compute Units (GPGPU) :
   - More Complex Execution Units : Beyond ALUs and FPUs, specialized units for accelerating matrix operations, tensor operations, etc., might be needed to support machine learning and high-performance computing workloads.
   - Memory Model and Synchronization Mechanisms : Support for shared memory, atomic operations, barrier synchronization, etc., to enable efficient parallel computation.
4. Memory Hierarchy :
   - Multi-level Caches : In addition to VRF, implementing L1/L2 caches and shared memory is crucial for optimizing data access performance.
   - Video Memory Controller : Interface and management for external DRAM (video memory).
5. Scheduling and Control Logic :
   - Warp/Thread Block Scheduling : More sophisticated schedulers to efficiently manage and dispatch a large number of warps/thread blocks, maximizing hardware utilization.
   - Context Switching : Efficiently switching between different tasks.
6. Bus and Interconnect : Internal data paths and interfaces with external systems (e.g., CPU).
7. Drivers and Software Stack : While this is a hardware project, a complete GPU ecosystem requires corresponding drivers and high-level APIs (e.g., OpenGL, Vulkan, DirectX, OpenCL, CUDA) to allow software developers to leverage the hardware capabilities.
8. Power and Performance Optimization : Clock gating, power management, and pipeline optimization should be considered during the RTL design phase to achieve target power and performance goals.

## License
MIT License



