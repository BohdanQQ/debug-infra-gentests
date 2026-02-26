package cz.cuni.mff.d3s.autodebugger.instrumentor.java;

import cz.cuni.mff.d3s.autodebugger.instrumentor.common.Instrumentor;
import cz.cuni.mff.d3s.autodebugger.instrumentor.common.modelling.InstrumentationModel;
import cz.cuni.mff.d3s.autodebugger.model.common.TempPathResolver;
import cz.cuni.mff.d3s.autodebugger.model.common.artifacts.InstrumentationResult;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import cz.cuni.mff.d3s.autodebugger.model.common.identifiers.ExportableValue;
import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.java.helper.DiSLPathHelper;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.JavaClassIdentifier;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.javatuples.Pair;

/**
 * DiSL-based instrumentor for Java applications.
 * Generates DiSL instrumentation classes and compiles them into JAR files
 * for runtime analysis using the DiSL dynamic instrumentation framework.
 */
@Slf4j
@Builder
public class DiSLInstrumentor implements Instrumentor {

    private final JavaClassIdentifier instrumentationClassName;

    private final JavaRunConfiguration runConfiguration;

    private Path generatedCodeOutputDirectory;

    @Getter
    private Path jarOutputPath;

    // Injected from the runner to control generation technique and credentials
    private final String strategyId;
    private final String apiKey;

    private Path resolveGeneratedCodeDir() {
        return generatedCodeOutputDirectory != null
            ? generatedCodeOutputDirectory
            : TempPathResolver.getGeneratedSourcesDir(runConfiguration.getOutputDirectory());
    }

    private Path resolveJarOutputPath() {
        return jarOutputPath != null
            ? jarOutputPath
            : TempPathResolver.getInstrumentationJarPath(runConfiguration.getOutputDirectory());
    }

    @Override
    public InstrumentationResult generateInstrumentation(InstrumentationModel model) {
        // Resolve effective paths using TempPathResolver
        Path effectiveGeneratedCodeDir = resolveGeneratedCodeDir();
        Path effectiveJarPath = resolveJarOutputPath();

        // Determine temp directory for identifier mapping files; allow override for tests
        Path identifiersBaseDir = Optional.ofNullable(System.getenv("AUTODEBUGGER_IDENTIFIERS_DIR"))
                .map(Path::of)
                .orElse(TempPathResolver.getIdentifiersDir(runConfiguration.getOutputDirectory()));
        var identifierMapping = serializeIdentifiers(identifiersBaseDir);
        var templateHandler = new JavaTemplateHandler(new JavaTemplateTransformer("${%s}"));

        // Ensure the Collector.jt template and CollectorRE.java are available in the output directory
        Path collectorTemplate = effectiveGeneratedCodeDir.resolve("Collector.jt");
        copyResourceTo(collectorTemplate, "/templates/java/disl-analysis/Collector.jt");
        copyResourceTo(effectiveGeneratedCodeDir.resolve("CollectorRE.java"), "/templates/java/disl-analysis/CollectorRE.java");

        Path tracesBaseDir = Optional.ofNullable(System.getenv("AUTODEBUGGER_TRACES_DIR"))
                .map(Path::of)
                .orElse(TempPathResolver.getTracesDir(runConfiguration.getOutputDirectory()));
        Path resultsBaseDir = Optional.ofNullable(System.getenv("AUTODEBUGGER_RESULTS_DIR"))
                .map(Path::of)
                .orElse(TempPathResolver.getResultsDir(runConfiguration.getOutputDirectory()));
        var traceFilePath = generateTraceFilePath(tracesBaseDir);
        var resultsListPath = generateResultsListPath(resultsBaseDir);
        // No need to set a system property; analyzer will read from runConfiguration output directory

        templateHandler.transformFile(
                collectorTemplate,
                effectiveGeneratedCodeDir.resolve("Collector.java"),
                Pair.with("PATH", identifierMapping.toAbsolutePath().toString()),
                Pair.with("TRACE_PATH", traceFilePath.toAbsolutePath().toString()),
                Pair.with("TRACE_MODE", runConfiguration.getTraceMode().name().toLowerCase()),
                Pair.with("PARAM_COUNT", String.valueOf(runConfiguration.getExportableValues().size())));
        var instrumentationJarPath = generateDiSLClass(effectiveGeneratedCodeDir, model)
                .flatMap(p -> compileDiSLClass(p, effectiveJarPath))
                .orElseThrow();
        return InstrumentationResult.builder()
                .primaryArtifact(instrumentationJarPath)
                .identifiersMappingPath(identifierMapping)
                .traceFilePath(traceFilePath)
                .resultsListPath(resultsListPath)
                .build();
    }

    private Optional<Path> generateDiSLClass(Path outputDir, InstrumentationModel model) {
        var generator = new DiSLClassGenerator(outputDir, model);
        return generator.generateCode();
    }

    private Optional<Path> compileDiSLClass(Path instrumentationSource, Path targetJarPath) {
        // Build classpath that includes the application JAR (needed for field owner class imports)
        List<Path> fullClasspath = new ArrayList<>(runConfiguration.getClasspathEntries());
        fullClasspath.add(runConfiguration.getApplicationPath());

        var compiler = new DiSLCompiler(targetJarPath, DiSLPathHelper.getDislClassPathRoot(runConfiguration),
                fullClasspath);
        return compiler.compileDiSLClass(instrumentationSource);
    }


    private void copyResourceTo(Path target, String resourcePath) {
        try (var in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) throw new RuntimeException("Missing resource: " + resourcePath);
            Files.createDirectories(target.getParent());
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy resource: " + resourcePath + " to " + target, e);
        }
    }


    private Path serializeIdentifiers(Path outputDirectory) {
        Map<Integer, ExportableValue> identifierMapping = new HashMap<>();
        for (ExportableValue value : runConfiguration.getExportableValues()) {
            identifierMapping.put(value.getInternalId(), value);
        }
        try {
            if (!outputDirectory.toFile().exists()) {
                if (outputDirectory.toFile().mkdirs()) {
                    log.info("Created directory {}", outputDirectory);
                } else {
                    log.error("Failed to create directory {}", outputDirectory);
                    return null;
                }
            }
            File mappingFile =
                    File.createTempFile("identifierMapping", ".ser", outputDirectory.toFile());

            try (FileOutputStream fileOutput = new FileOutputStream(mappingFile);
                 ObjectOutputStream objectStream = new ObjectOutputStream(fileOutput)) {
                objectStream.writeObject(identifierMapping);
            }
            return mappingFile.toPath();
        } catch (IOException e) {
            log.error("Failed to serialize identifier mapping", e);
            return null;
        }
    }

    private Path generateTraceFilePath(Path outputDirectory) {
        try {
            if (!Files.exists(outputDirectory)) {
                Files.createDirectories(outputDirectory);
            }
            String runId = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                    .format(java.time.LocalDateTime.now()) + "-" + java.util.UUID.randomUUID();
            String fileName = String.format("trace-%s.ser", runId);
            return outputDirectory.resolve(fileName);
        } catch (IOException e) {
            log.error("Failed to create trace file path", e);
            throw new RuntimeException(e);
        }
    }

    private Path generateResultsListPath(Path outputDirectory) {
        try {
            if (!Files.exists(outputDirectory)) {
                Files.createDirectories(outputDirectory);
            }
            String runId = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                    .format(java.time.LocalDateTime.now()) + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            String fileName = String.format("generated-tests-%s.lst", runId);
            return outputDirectory.resolve(fileName);
        } catch (IOException e) {
            log.error("Failed to create results list path", e);
            throw new RuntimeException(e);
        }
    }
}
