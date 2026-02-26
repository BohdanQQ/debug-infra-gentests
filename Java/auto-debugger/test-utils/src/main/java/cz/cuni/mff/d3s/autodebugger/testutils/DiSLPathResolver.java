package cz.cuni.mff.d3s.autodebugger.testutils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Utility for resolving DiSL home path consistently across tests and production code.
 * 
 * Resolution order:
 * 1. DISL_HOME environment variable (if set and valid)
 * 2. Relative to Git repository root (../disl from repo root)
 * 3. Empty if not found (tests should skip)
 */
public final class DiSLPathResolver {
    
    public static final String DISL_HOME_ENV = "DISL_HOME";
    
    private DiSLPathResolver() {}
    
    /**
     * Resolves the DiSL home path.
     * 
     * @return Optional containing valid DiSL home path, or empty if not available
     */
    public static Optional<Path> getDislHomePath() {
        // 1. Check DISL_HOME environment variable
        String envDislHome = System.getenv(DISL_HOME_ENV);
        if (envDislHome != null && !envDislHome.isBlank()) {
            Path envPath = Path.of(envDislHome);
            if (isValidDislHome(envPath)) {
                return Optional.of(envPath.toAbsolutePath().normalize());
            }
        }
        
        // 2. Try to find relative to project/repository root
        Optional<Path> repoRoot = findRepositoryRoot();
        if (repoRoot.isPresent()) {
            // DiSL is typically at ../disl relative to repository root
            Path relativeDislPath = repoRoot.get().getParent().resolve("disl");
            if (isValidDislHome(relativeDislPath)) {
                return Optional.of(relativeDislPath.toAbsolutePath().normalize());
            }
        }
        
        return Optional.empty();
    }
    
    /**
     * Gets the DiSL home path or throws if not available.
     * Use this when DiSL is required and the test should fail if not available.
     */
    public static Path requireDislHomePath() {
        return getDislHomePath()
            .orElseThrow(() -> new IllegalStateException(
                "DiSL home not found. Set " + DISL_HOME_ENV + " environment variable " +
                "or ensure DiSL is at ../disl relative to repository root."));
    }
    
    /**
     * Checks if the given path is a valid DiSL installation.
     */
    public static boolean isValidDislHome(Path path) {
        if (path == null || !Files.isDirectory(path)) {
            return false;
        }
        // Check for required DiSL structure
        Path dislPy = path.resolve("bin/disl.py");
        Path outputLib = path.resolve("output/lib");
        return Files.exists(dislPy) && Files.isDirectory(outputLib);
    }
    
    /**
     * Finds the repository root by looking for .git directory.
     */
    private static Optional<Path> findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        
        // Walk up the directory tree looking for .git
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git"))) {
                return Optional.of(current);
            }
            current = current.getParent();
        }
        
        return Optional.empty();
    }
    
    /**
     * Resolves DiSL path from CLI argument, falling back to DISL_HOME env var.
     * 
     * @param cliDislHomePath CLI argument value (may be null)
     * @return Resolved DiSL home path, never null
     * @throws IllegalArgumentException if DiSL path cannot be resolved
     */
    public static Path resolveFromCliOrEnv(String cliDislHomePath) {
        // 1. CLI argument takes precedence
        if (cliDislHomePath != null && !cliDislHomePath.isBlank()) {
            return Path.of(cliDislHomePath).toAbsolutePath().normalize();
        }
        
        // 2. Fall back to DISL_HOME environment variable or auto-detection
        return getDislHomePath()
            .orElseThrow(() -> new IllegalArgumentException(
                "DiSL home path not specified. Use --disl-home argument or set " + 
                DISL_HOME_ENV + " environment variable."));
    }
}

