package cz.cuni.mff.d3s.autodebugger.analyzer.common;

import cz.cuni.mff.d3s.autodebugger.model.common.artifacts.InstrumentationResult;

/**
 * Interface for analyzing instrumented applications and collecting runtime traces.
 */
public interface Analyzer {

    /**
     * Executes analysis on the instrumented application and returns analysis artifacts.
     * This method only performs the instrumentation execution and trace collection.
     *
     * @param instrumentation Instrumentation artifacts produced by the instrumentor
     * @return AnalysisResult containing paths to trace file and identifier mapping
     */
    AnalysisResult executeAnalysis(InstrumentationResult instrumentation);

    /**
     * Validates that the analyzer can process the given instrumentation.
     */
    void validateInstrumentation(InstrumentationResult instrumentation);
}
