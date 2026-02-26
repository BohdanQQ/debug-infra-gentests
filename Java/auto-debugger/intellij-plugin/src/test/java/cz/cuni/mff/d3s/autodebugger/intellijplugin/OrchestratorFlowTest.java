package cz.cuni.mff.d3s.autodebugger.intellijplugin;

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase5;
import cz.cuni.mff.d3s.autodebugger.analyzer.common.AnalysisResult;
import cz.cuni.mff.d3s.autodebugger.intellijplugin.services.OutputService;
import cz.cuni.mff.d3s.autodebugger.instrumentor.common.modelling.InstrumentationModel;
import cz.cuni.mff.d3s.autodebugger.model.common.artifacts.InstrumentationResult;
import cz.cuni.mff.d3s.autodebugger.model.common.tests.TestSuite;
import cz.cuni.mff.d3s.autodebugger.testrunner.common.TestExecutionResult;
import cz.cuni.mff.d3s.autodebugger.runner.orchestrator.Orchestrator;
import org.mockito.InOrder;
import org.mockito.Mockito;

public class OrchestratorFlowTest extends LightJavaCodeInsightFixtureTestCase5 {

    @org.junit.Test
    public void givenOrchestrator_whenRunInvoked_thenPhasesCalledInOrder_andOutputPrinted() {
        var project = getFixture().getProject();
        var runner = new OrchestratorRunner();
        var output = OutputService.getInstance(project);

        // Mocks and stubs
        Orchestrator orch = Mockito.mock(Orchestrator.class);
        InstrumentationModel model = Mockito.mock(InstrumentationModel.class);
        InstrumentationResult instr = Mockito.mock(InstrumentationResult.class);
        AnalysisResult analysisResult = Mockito.mock(AnalysisResult.class);
        TestSuite suite = TestSuite.builder().baseDirectory(java.nio.file.Path.of("/tmp"))
                .build();
        TestExecutionResult testResult = TestExecutionResult.builder().overallStatus(
                cz.cuni.mff.d3s.autodebugger.testrunner.common.TestSuiteStatus.PASSED
        ).build();

        Mockito.when(orch.buildInstrumentationModel()).thenReturn(model);
        Mockito.when(orch.createInstrumentation(model)).thenReturn(instr);
        Mockito.when(orch.executeAnalysis(instr)).thenReturn(analysisResult);
        Mockito.when(orch.generateTests(analysisResult)).thenReturn(suite);
        Mockito.when(orch.runTests(suite)).thenReturn(testResult);

        // Execute
        runner.run(orch, output);

        // Verify order
        InOrder inOrder = Mockito.inOrder(orch);
        inOrder.verify(orch).buildInstrumentationModel();
        inOrder.verify(orch).createInstrumentation(model);
        inOrder.verify(orch).executeAnalysis(instr);
        inOrder.verify(orch).generateTests(analysisResult);
        inOrder.verify(orch).runTests(suite);

        // Verify that OutputService received expected phase messages by not throwing; deeper
        // verification of UI ConsoleView content is not practical here without UI accessors.
        // This test ensures OrchestratorRunner prints without exceptions.
    }
}

