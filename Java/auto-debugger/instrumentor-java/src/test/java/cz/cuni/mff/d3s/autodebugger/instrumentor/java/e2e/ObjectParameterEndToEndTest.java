package cz.cuni.mff.d3s.autodebugger.instrumentor.java.e2e;

import cz.cuni.mff.d3s.autodebugger.analyzer.common.AnalysisResult;
import cz.cuni.mff.d3s.autodebugger.analyzer.java.DiSLAnalyzer;
import cz.cuni.mff.d3s.autodebugger.instrumentor.java.DiSLInstrumentor;
import cz.cuni.mff.d3s.autodebugger.model.common.TraceMode;
import cz.cuni.mff.d3s.autodebugger.model.common.artifacts.InstrumentationResult;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.ObjectSnapshot;
import cz.cuni.mff.d3s.autodebugger.model.common.trace.Trace;
import cz.cuni.mff.d3s.autodebugger.model.java.JavaRunConfiguration;
import cz.cuni.mff.d3s.autodebugger.model.java.identifiers.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test for object parameter instrumentation with DiSL.
 * Tests that object parameters are correctly captured as ObjectSnapshots
 * with their field values serialized through the JSON-based approach.
 */
class ObjectParameterEndToEndTest extends DiSLEndToEndTestBase {

    @Test
    void givenObjectParameter_whenInstrumenting_thenTraceContainsObjectSnapshot() throws Exception {
        // Given: PersonService.greet(Person) with Person{name="Alice", age=30}
        Path targetJar = compileAndPackageTarget(createPersonServiceTarget(), "person-service.jar", "PersonService");

        JavaMethodIdentifier targetMethod = createGreetMethodIdentifier();
        List<JavaValueIdentifier> exportableValues = createPersonArgumentIdentifiers();

        JavaRunConfiguration runConfig = createRunConfigurationBuilder(targetJar, targetMethod, exportableValues)
                .traceMode(TraceMode.NAIVE).build();

        JavaClassIdentifier instrumentationClassName = createInstrumentationClassName();
        DiSLInstrumentor instrumentor = createInstrumentor(runConfig, instrumentationClassName);
        DiSLAnalyzer analyzer = createAnalyzer(runConfig);

        // When: Generate instrumentation and execute analysis
        InstrumentationResult instrumentation = generateInstrumentation(instrumentor, targetMethod, exportableValues);
        AnalysisResult analysisResult = executeAnalysis(analyzer, instrumentation);

        // Then: Trace contains object snapshot with field values
        Trace trace = deserializeTrace(analysisResult.getTraceFilePath());
        Map<Integer, JavaValueIdentifier> mapping = deserializeIdentifierMapping(instrumentation.getIdentifiersMappingPath());

        int slot = findSlotForArgument(mapping, 0);
        Set<ObjectSnapshot> objectValues = trace.getObjectValues(slot);

        assertFalse(objectValues.isEmpty(), "Trace should contain object values for Person parameter");
        
        ObjectSnapshot snapshot = objectValues.iterator().next();
        assertEquals("Person", snapshot.getSimpleClassName(), "Captured object should be Person");
        assertEquals("Alice", snapshot.getField("name"), "Person.name should be 'Alice'");
        assertEquals(30, snapshot.getField("age"), "Person.age should be 30");
    }

    // ========== Helper Methods ==========

    private String createPersonServiceTarget() {
        return """
                class Person {
                    public String name;
                    public int age;
                    public Person(String name, int age) {
                        this.name = name;
                        this.age = age;
                    }
                }
                
                public class PersonService {
                    public String greet(Person person) {
                        return "Hello, " + person.name + "!";
                    }
                    
                    public static void main(String[] args) {
                        PersonService service = new PersonService();
                        Person alice = new Person("Alice", 30);
                        String greeting = service.greet(alice);
                        System.out.println(greeting);
                    }
                }
                """;
    }

    private JavaMethodIdentifier createGreetMethodIdentifier() {
        return createMethodIdentifier("", "PersonService", "greet", "java.lang.String", List.of("Person"));
    }

    private List<JavaValueIdentifier> createPersonArgumentIdentifiers() {
        return List.of(new JavaArgumentIdentifier(
                ArgumentIdentifierParameters.builder()
                        .argumentSlot(0)
                        .variableType("Person")
                        .build()));
    }

    private JavaClassIdentifier createInstrumentationClassName() {
        return new JavaClassIdentifier(ClassIdentifierParameters.builder()
                .packageIdentifier(JavaPackageIdentifier.DEFAULT_PACKAGE)
                .className("PersonServiceInstrumentation")
                .build());
    }
}

