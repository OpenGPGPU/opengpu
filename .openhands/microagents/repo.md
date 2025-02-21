---
name: repo
type: repo
agent: CodeActAgent
---
This repository contains the code for OpenGPU, a simple GPU hardware developed by chisel language.

Setup:
To set up the entire repo, you should install nix first. Then run the command "nix --experimental-features 'nix-command flakes' develop -c bash" to enter the development environment. Or we can use Makefile for run and test directly.

If adding a new module in src, always add appropriate unit tests in tests.
