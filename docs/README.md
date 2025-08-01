# OPENGPU Documentation

Welcome to the OPENGPU documentation. This directory contains comprehensive documentation for the OPENGPU project, a simplified GPU implementation built with Chisel.

## Documentation Overview

### 📋 [Architecture Guide](ARCHITECTURE.md)
Complete system architecture overview including:
- High-level system design
- Core components and their interactions
- Memory hierarchy and cache system
- RISC-V Vector extension support
- TileLink integration details
- Configuration parameters and development status

### 🔗 [TileLink Integration Guide](TILELINK_INTEGRATION.md)
Detailed guide for TileLink protocol implementation:
- TileLink protocol overview and benefits
- Memory component integration (I-Cache, G-Cache)
- Memory access flow and data paths
- Implementation details and configuration
- Testing and verification strategies
- Migration from AXI4 to TileLink

### 🛠️ [Development Guide](DEVELOPMENT_GUIDE.md)
Complete development workflow and setup:
- Environment setup and prerequisites
- Project structure and build system
- Testing framework and test writing
- Debugging techniques and common issues
- Performance optimization guidelines
- Contributing guidelines and code standards

## Quick Start

1. **New to OPENGPU?** Start with the [Architecture Guide](ARCHITECTURE.md) to understand the system design
2. **Setting up development?** Follow the [Development Guide](DEVELOPMENT_GUIDE.md) for environment setup
3. **Working with TileLink?** Check the [TileLink Integration Guide](TILELINK_INTEGRATION.md)

## Project Status

OPENGPU is currently in active development with the following completed components:

✅ **Core Pipeline**: Instruction fetch, decode, and execution  
✅ **Memory System**: I-Cache, G-Cache with TileLink interfaces  
✅ **Virtual Memory**: TLB and PTW with full virtual memory support  
✅ **Vector Processing**: RISC-V Vector extension support  
✅ **Testing Framework**: Comprehensive test suite  

🔄 **In Progress**: Graphics rendering pipeline, advanced scheduling  
📋 **Planned**: Power management, advanced coherence protocols  

## Contributing

When contributing to OPENGPU:

1. Follow the development guidelines in [Development Guide](DEVELOPMENT_GUIDE.md)
2. Update relevant documentation for new features
3. Add tests for new functionality
4. Use the established commit message format

## Getting Help

- Check the documentation in this directory
- Review test examples in the `tests/` directory
- Search existing issues and discussions
- Ask questions in project discussions

---

For the main project overview, see the [main README](../README.md). 
