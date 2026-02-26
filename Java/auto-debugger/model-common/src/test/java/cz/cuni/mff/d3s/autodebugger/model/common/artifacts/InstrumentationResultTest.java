package cz.cuni.mff.d3s.autodebugger.model.common.artifacts;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InstrumentationResultTest {

    @Test
    void givenMinimal_whenBuild_thenHasPrimaryArtifact() {
        // Note: resultsListPath can be null, but this will cause the analyzer
        // to return an empty test list. In production, DiSLInstrumentor always sets this field.
        var res = InstrumentationResult.builder()
                .primaryArtifact(Path.of("/tmp/instr.jar"))
                .build();
        assertEquals(Path.of("/tmp/instr.jar"), res.getPrimaryArtifact());
        assertTrue(res.getAdditionalArtifacts().isEmpty());
        assertNull(res.getIdentifiersMappingPath());
        assertNull(res.getResultsListPath());
        assertNull(res.getTraceFilePath());
    }

    @Test
    void givenFull_whenBuild_thenExposesAllFields() {
        var res = InstrumentationResult.builder()
                .primaryArtifact(Path.of("/tmp/instr.jar"))
                .artifact(Path.of("/tmp/a.txt"))
                .identifiersMappingPath(Path.of("/tmp/ids.ser"))
                .resultsListPath(Path.of("/tmp/generated-tests.lst"))
                .traceFilePath(Path.of("/tmp/trace.ser"))
                .build();
        assertEquals(1, res.getAdditionalArtifacts().size());
        assertEquals(Path.of("/tmp/a.txt"), res.getAdditionalArtifacts().getFirst());
        assertEquals(Path.of("/tmp/ids.ser"), res.getIdentifiersMappingPath());
        assertEquals(Path.of("/tmp/generated-tests.lst"), res.getResultsListPath());
        assertEquals(Path.of("/tmp/trace.ser"), res.getTraceFilePath());
    }
}

