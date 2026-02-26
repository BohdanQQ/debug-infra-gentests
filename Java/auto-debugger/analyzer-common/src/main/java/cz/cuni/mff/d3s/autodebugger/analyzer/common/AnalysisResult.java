package cz.cuni.mff.d3s.autodebugger.analyzer.common;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.nio.file.Path;

/**
 * Result of the analysis phase. Contains paths to serialized artifacts
 * produced during analysis that downstream components need for test generation.
 */
@Getter
@Builder
@EqualsAndHashCode
@ToString
public class AnalysisResult {

    /** Path to the serialized Trace object produced by analysis. */
    private final Path traceFilePath;

    /** Path to the serialized identifier mapping needed for test generation. */
    private final Path identifiersMappingPath;

    /** Base output directory where generated artifacts are stored. */
    private final Path outputDirectory;
}

