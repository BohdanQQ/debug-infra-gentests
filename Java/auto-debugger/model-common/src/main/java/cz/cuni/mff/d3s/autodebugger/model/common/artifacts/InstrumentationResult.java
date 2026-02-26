package cz.cuni.mff.d3s.autodebugger.model.common.artifacts;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Singular;
import lombok.ToString;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Result of the instrumentation phase. Describes the artifacts and conventions
 * produced by an Instrumentor so downstream components can consume them without
 * relying on ad-hoc filesystem scanning.
 */
@Getter
@Builder
@EqualsAndHashCode
@ToString
public class InstrumentationResult {

    /** Primary instrumentation artifact (e.g., DiSL instrumentation JAR). */
    private final Path primaryArtifact;

    /** Additional artifacts produced by instrumentation. */
    @Singular("artifact")
    private final List<Path> additionalArtifacts;

    /** Path to the serialized identifier mapping file, if produced. */
    private final Path identifiersMappingPath;

    /** Exact path where the analysis/collector will append generated test paths. */
    private final Path resultsListPath;

    /** Path where the serialized Trace object will be written by the collector. */
    private final Path traceFilePath;

    public List<Path> getAdditionalArtifacts() {
        return additionalArtifacts == null ? List.of() : Collections.unmodifiableList(additionalArtifacts);
    }
}

