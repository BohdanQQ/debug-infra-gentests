# DiSL Patches for Auto-Debugger

These patches fix issues in the DiSL framework required for running auto-debugger on macOS ARM64 (Apple Silicon).

## Patches

| Patch | Description |
|-------|-------------|
| `0001-fix-disl-use-e_opts-instead-of-s_opts-in-run_evaluat.patch` | Fixes evaluation server options in `disl.py` |
| `0002-build-add-aarch64-architecture-support.patch` | Adds aarch64 architecture to `build.xml` |
| `0003-disl-agent-macos-arm64-linker-flags.patch` | macOS ARM64 linker flags for DiSL agent |
| `0004-shvm-agent-disable-pthread-macos.patch` | Disables pthread linking on macOS (not needed) |

## Applying Patches

From your DiSL repository root:

```bash
# Apply all patches
cd /path/to/disl
for patch in /path/to/auto-debugger/patches/disl/*.patch; do
  git apply "$patch"
done

# Or apply individually
git apply /path/to/auto-debugger/patches/disl/0001-fix-disl-use-e_opts-instead-of-s_opts-in-run_evaluat.patch
git apply /path/to/auto-debugger/patches/disl/0002-build-add-aarch64-architecture-support.patch
git apply /path/to/auto-debugger/patches/disl/0003-disl-agent-macos-arm64-linker-flags.patch
git apply /path/to/auto-debugger/patches/disl/0004-shvm-agent-disable-pthread-macos.patch
```

## Notes

- **Patch 0003** contains hardcoded paths for macOS SDK and LLVM. You may need to adjust:
  - `/Library/Developer/CommandLineTools/SDKs/MacOSX14.sdk` - your SDK version
  - `/opt/homebrew/Cellar/llvm/21.1.7/lib/clang/21/lib/darwin/libclang_rt.osx.a` - your LLVM installation

- After applying patches, rebuild DiSL:
  ```bash
  ant clean
  ant
  ```

## Platform

These patches are specifically for **macOS ARM64 (Apple Silicon)**. Linux and x86_64 users may not need all patches.

