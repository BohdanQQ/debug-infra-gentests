# Multi-threading and CRIU demo

This example demonstrates the multi-threding support in ACF.

Run `./run-mt-demo.sh` for non-checkpointing implementation.
Run `sudo ./run-criu-demo.sh` for checkpointing implementation.

Note that checkpointing implementation requires root (true root, this cannot be run in a container).

Outputs are generated in `./results/` directory.

Timing on the author's machine (all tests ran on a QEMU VM, Fedora Workstation 41 Guest, NixOS 25.11 `nixpkgs` rev `d6df3513510aa548c83868fd22bfddd0a8c0a0d4` host):

non-checkpointing: ~39 seconds
checkpointing: ~26 seconds