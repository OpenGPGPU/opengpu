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

## License
MIT License



