# Development Guide

## Getting Started

### Prerequisites

- **Java 11+**: Required for Mill build system
- **Scala 2.13+**: For Chisel development
- **Git**: For version control
- **Nix** (optional): For reproducible development environment

### Environment Setup

#### Using Nix (Recommended)
```bash
# Clone the repository
git clone <repository-url>
cd opengpu

# Enter the development environment
make dev

```


## Project Structure

```
opengpu/
├── src/                    # Source code
│   ├── core/              # Core GPU components
│   ├── dispatcher/        # Job dispatching logic
│   ├── fpu/              # Floating-point units
│   └── vector/           # Vector processing units
├── tests/                 # Test files
├── docs/                  # Documentation
├── depends/               # Dependencies
├── build.sc               # Mill build configuration
├── common.sc              # Common build settings
└── README.md             # Project overview
```

## Building the Project

### Basic Build Commands

```bash

# Run tests
make test

# Generate Verilog

```

### Build Configuration

The project uses Mill as the build system with the following key files:

- **build.sc**: Main build configuration
- **common.sc**: Common build settings and dependencies
- **flake.nix**: Nix development environment

## Testing

### Running Tests

```bash
# Run all tests
make test

# Run specific test
make ztest MYOPT="PTWDCacheTest"
```

### Test Structure

Tests are organized by component:

- **PTWDCacheTest**: Page Table Walker and Data Cache integration
- **FrontendTest**: Instruction fetch and frontend pipeline
- **ALUExecutionTest**: ALU execution unit tests
- **FPUTest**: Floating-point unit tests

### Writing Tests

#### Basic Test Template

```scala
import chisel3._
import chisel3.simulator.VCDHackedEphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class MyModuleTest extends AnyFlatSpec {
  
  behavior.of("MyModule")
  
  it should "handle basic functionality" in {
    val params = MyParameter(...)
    
    simulate(new MyModule(params), "test_name") { dut =>
      // Test implementation
      dut.clock.step()
      dut.io.input.poke(value)
      
      // Verify results
      dut.io.output.expect(expected)
    }
  }
}
```

#### Clock and Reset Management

```scala
// Initialize
dut.clock.step()
dut.reset.poke(true.B)
dut.clock.step()
dut.reset.poke(false.B)

// Drive signals
dut.io.signal.poke(value)
dut.clock.step()

// Check results
dut.io.output.expect(expected)
```

## Development Workflow

### 1. Feature Development

```bash
# Create feature branch
git checkout -b feature/new-component

# Make changes
# Edit source files

# Run tests
make test

# Commit changes
git add .
git commit -m "feat: add new component"
```

### 2. Code Quality

#### Code Style
- Use Scala formatting with scalafmt
- Follow Chisel coding conventions
- Add comprehensive comments for complex logic

#### Testing
- Write tests for all new functionality
- Maintain high test coverage
- Use descriptive test names

### 3. Documentation
- Update relevant documentation
- Add inline comments for complex logic
- Document parameter configurations

## Debugging

### Simulation Debugging

#### Waveform Generation
```scala
// Enable VCD generation
simulate(new MyModule(params), "test_name", Seq(WriteVcdAnnotation)) { dut =>
  // Test code
}
```

#### Signal Monitoring
```scala
// Monitor signals during simulation
dut.io.signal.peek() // Read signal value
dut.io.signal.expect(expected) // Assert signal value
```

### Common Issues

#### Clock Issues
- Ensure proper clock connection for FixedIORawModule
- Use `withClockAndReset` for ImplicitClock modules
- Check clock domain crossing

#### Memory Issues
- Verify TileLink interface connections
- Check cache coherence protocols
- Validate memory access patterns

## Performance Optimization

### 1. Pipeline Optimization
- Minimize pipeline bubbles
- Optimize critical path timing
- Balance pipeline stages

### 2. Memory Optimization
- Optimize cache hit rates
- Minimize memory access latency
- Efficient cache line utilization

### 3. Power Optimization
- Implement clock gating
- Optimize for typical workloads
- Consider dynamic power scaling

## Contributing

### Development Guidelines

1. **Code Style**: Follow existing code style and conventions
2. **Testing**: Add tests for new functionality
3. **Documentation**: Update relevant documentation
4. **Review**: Submit pull requests for review

### Commit Message Format

```
type: brief description

- Detailed change description
- Additional context if needed

Fixes #issue-number
```

Types:
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes
- `refactor`: Code refactoring
- `test`: Test changes
- `chore`: Build/tool changes

### Pull Request Process

1. Create feature branch
2. Make changes with tests
3. Update documentation
4. Submit pull request
5. Address review comments
6. Merge after approval

## Troubleshooting

### Common Build Issues

#### Mill Issues
```bash
# Clean and rebuild
make clean
```

#### Scala Version Issues
```bash
# Check Scala version
scala -version

# Update Scala if needed
```

### Getting Help

- Check existing documentation
- Review test examples
- Search issue tracker
- Ask in discussions 
