package cz.cuni.mff.d3s.autodebugger.model.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Utility class for resolving temporary paths and directories used by the auto-debugger.
 * Provides centralized path management for output directories, generated sources, libraries,
 * identifiers, traces, and results.
 * 
 * <p>The base directory can be overridden using the AUTODEBUGGER_OUTPUT_DIR environment variable.
 * If not set, the system temporary directory is used with an "autodebugger" namespace subdirectory.
 * 
 * <p>This class follows the utility class pattern with a private constructor and static methods only.
 */
public final class TempPathResolver {
    
    /**
     * Environment variable to override the base output directory.
     */
    public static final String OUTPUT_DIR_ENV = "AUTODEBUGGER_OUTPUT_DIR";
    
    /**
     * Subdirectory name under system temp directory when OUTPUT_DIR_ENV is not set.
     */
    public static final String AUTODEBUGGER_NAMESPACE = "autodebugger";
    
    /**
     * Subdirectory name for generated source files.
     */
    public static final String GENERATED_SOURCES_DIR = "generated-sources";
    
    /**
     * Subdirectory name for library files (e.g., instrumentation JARs).
     */
    public static final String LIBS_DIR = "libs";
    
    /**
     * Subdirectory name for identifier mapping files.
     */
    public static final String IDENTIFIERS_DIR = "identifiers";
    
    /**
     * Subdirectory name for trace files.
     */
    public static final String TRACES_DIR = "traces";
    
    /**
     * Subdirectory name for results files.
     */
    public static final String RESULTS_DIR = "results";
    
    /**
     * Date-time format for run directory timestamps.
     */
    private static final DateTimeFormatter RUN_ID_FORMATTER = 
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    
    /**
     * Private constructor to prevent instantiation.
     */
    private TempPathResolver() {}
    
    /**
     * Gets the base directory for auto-debugger output.
     * 
     * <p>Resolution order:
     * <ol>
     *   <li>AUTODEBUGGER_OUTPUT_DIR environment variable (if set)</li>
     *   <li>System temp directory + "autodebugger" namespace</li>
     * </ol>
     * 
     * @return Path to the base autodebugger directory
     */
    public static Path getBaseDirectory() {
        String envOutputDir = System.getenv(OUTPUT_DIR_ENV);
        if (envOutputDir != null && !envOutputDir.isBlank()) {
            return Path.of(envOutputDir).toAbsolutePath().normalize();
        }
        
        return Path.of(System.getProperty("java.io.tmpdir"))
            .resolve(AUTODEBUGGER_NAMESPACE)
            .toAbsolutePath()
            .normalize();
    }
    
    /**
     * Creates a unique run directory with timestamp and UUID.
     * 
     * <p>The directory name format is: yyyyMMdd-HHmmss-SSS-{first 8 chars of UUID}
     * 
     * @return Path to the created run directory
     * @throws IOException if directory creation fails
     */
    public static Path createRunDirectory() throws IOException {
        String timestamp = RUN_ID_FORMATTER.format(LocalDateTime.now());
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        String runId = timestamp + "-" + uuid;
        
        Path runDir = getBaseDirectory().resolve(runId);
        Files.createDirectories(runDir);
        
        return runDir;
    }
    
    /**
     * Gets a default output directory for the current run.
     * This is a safe version of createRunDirectory() that doesn't throw exceptions.
     * 
     * <p>If directory creation fails, returns a path that may not exist yet.
     * Callers should handle directory creation as needed.
     * 
     * @return Path to a unique run directory
     */
    public static Path getDefaultOutputDirectory() {
        try {
            return createRunDirectory();
        } catch (IOException e) {
            // Fallback: return the path without creating it
            String timestamp = RUN_ID_FORMATTER.format(LocalDateTime.now());
            String uuid = UUID.randomUUID().toString().substring(0, 8);
            String runId = timestamp + "-" + uuid;
            return getBaseDirectory().resolve(runId);
        }
    }
    
    /**
     * Gets the generated sources directory within the given output directory.
     * 
     * @param outputDirectory The base output directory for the run
     * @return Path to the generated-sources subdirectory
     */
    public static Path getGeneratedSourcesDir(Path outputDirectory) {
        return outputDirectory.resolve(GENERATED_SOURCES_DIR);
    }
    
    /**
     * Gets the libraries directory within the given output directory.
     *
     * @param outputDirectory The base output directory for the run
     * @return Path to the libs subdirectory
     */
    public static Path getLibsDir(Path outputDirectory) {
        return outputDirectory.resolve(LIBS_DIR);
    }

    /**
     * Gets the identifiers directory within the given output directory.
     *
     * @param outputDirectory The base output directory for the run
     * @return Path to the identifiers subdirectory
     */
    public static Path getIdentifiersDir(Path outputDirectory) {
        return outputDirectory.resolve(IDENTIFIERS_DIR);
    }

    /**
     * Gets the traces directory within the given output directory.
     *
     * @param outputDirectory The base output directory for the run
     * @return Path to the traces subdirectory
     */
    public static Path getTracesDir(Path outputDirectory) {
        return outputDirectory.resolve(TRACES_DIR);
    }

    /**
     * Gets the results directory within the given output directory.
     *
     * @param outputDirectory The base output directory for the run
     * @return Path to the results subdirectory
     */
    public static Path getResultsDir(Path outputDirectory) {
        return outputDirectory.resolve(RESULTS_DIR);
    }

    /**
     * Gets the path to the instrumentation JAR file within the given output directory.
     *
     * @param outputDirectory The base output directory for the run
     * @return Path to the instrumentation.jar file in the libs subdirectory
     */
    public static Path getInstrumentationJarPath(Path outputDirectory) {
        return getLibsDir(outputDirectory).resolve("instrumentation.jar");
    }
}
