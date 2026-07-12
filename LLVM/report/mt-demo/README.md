# Multi-threading and CRIU demo

This example demonstrates the multi-threding support in ACF.

Run `./run-mt-demo.sh` for non-checkpointing implementation.
Run `sudo ./run-criu-demo.sh` for checkpointing implementation.

Note that checkpointing implementation requires root (true root, this cannot be run in a container).

Outputs are generated in `./results/` directory.

Timing on the author's machine:

non-checkpointing: ~39 seconds
checkpointing: ~26 seconds