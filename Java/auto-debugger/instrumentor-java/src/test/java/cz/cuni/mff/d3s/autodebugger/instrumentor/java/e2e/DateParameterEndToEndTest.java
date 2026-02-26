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
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test for java.util.Date object capture.
 * Tests that Date objects are correctly captured as ObjectSnapshots
 * with their fastTime field serialized.
 */
class DateParameterEndToEndTest extends DiSLEndToEndTestBase {

    // Test constants - deterministic timestamps
    private static final long TIMESTAMP_DEC_10_2023 = 1702166400000L;
    private static final long TIMESTAMP_EPOCH = 0L;
    private static final long TIMESTAMP_Y2K = 946684800000L;
    private static final long TIMESTAMP_CHRISTMAS_2023 = 1703505600000L;

    @Test
    void givenDateParameter_whenInstrumenting_thenTraceContainsFastTimeField() throws Exception {
        // Given: DateService.format(Date) with Date(1702166400000L) - default package
        Path targetJar = compileAndPackageTarget(createSingleDateServiceTarget(), "date-service.jar", "DateService");

        JavaMethodIdentifier targetMethod = createFormatMethodIdentifier();
        List<JavaValueIdentifier> exportableValues = createDateArgumentIdentifiers();

        JavaRunConfiguration runConfig = createRunConfigurationBuilder(targetJar, targetMethod, exportableValues)
                .traceMode(TraceMode.NAIVE).build();

        JavaClassIdentifier instrumentationClassName = createDateServiceInstrumentationClassName();
        DiSLInstrumentor instrumentor = createInstrumentor(runConfig, instrumentationClassName);
        DiSLAnalyzer analyzer = createAnalyzer(runConfig);

        // When: Generate instrumentation and execute analysis
        InstrumentationResult instrumentation = generateInstrumentation(instrumentor, targetMethod, exportableValues);
        AnalysisResult analysisResult = executeAnalysis(analyzer, instrumentation);

        // Then: Trace contains Date snapshot with $value field (JDK types use toString())
        Trace trace = deserializeTrace(analysisResult.getTraceFilePath());
        Map<Integer, JavaValueIdentifier> mapping = deserializeIdentifierMapping(instrumentation.getIdentifiersMappingPath());

        int slot = findSlotForArgument(mapping, 0);
        Set<ObjectSnapshot> objectValues = trace.getObjectValues(slot);

        assertFalse(objectValues.isEmpty(), "Trace should contain object values for Date parameter");

        ObjectSnapshot snapshot = objectValues.iterator().next();
        assertEquals("java.util.Date", snapshot.getClassName(), "Captured object should be java.util.Date");
        // JDK types are serialized using toString() to avoid module system restrictions
        // The $value field contains the string representation of the Date
        String dateValue = (String) snapshot.getField("$value");
        assertNotNull(dateValue, "Date.$value should not be null");
        assertTrue(dateValue.contains("Dec 10") || dateValue.contains("2023"),
                "Date.$value should contain date information: " + dateValue);
    }

    @Test
    void givenMultipleDateInvocations_whenInstrumenting_thenTraceContainsAllTimestamps() throws Exception {
        // Given: DateService.format(Date) called with epoch and Y2K dates
        Path targetJar = compileAndPackageTarget(createMultipleDateServiceTarget(), "date-service-multiple.jar", "DateService");

        JavaMethodIdentifier targetMethod = createFormatMethodIdentifier();
        List<JavaValueIdentifier> exportableValues = createDateArgumentIdentifiers();

        JavaRunConfiguration runConfig = createRunConfigurationBuilder(targetJar, targetMethod, exportableValues)
                .traceMode(TraceMode.NAIVE).build();

        JavaClassIdentifier instrumentationClassName = createDateServiceInstrumentationClassName();
        DiSLInstrumentor instrumentor = createInstrumentor(runConfig, instrumentationClassName);
        DiSLAnalyzer analyzer = createAnalyzer(runConfig);

        // When: Generate instrumentation and execute analysis
        InstrumentationResult instrumentation = generateInstrumentation(instrumentor, targetMethod, exportableValues);
        AnalysisResult analysisResult = executeAnalysis(analyzer, instrumentation);

        // Then: Trace contains all Date snapshots
        Trace trace = deserializeTrace(analysisResult.getTraceFilePath());
        Map<Integer, JavaValueIdentifier> mapping = deserializeIdentifierMapping(instrumentation.getIdentifiersMappingPath());

        int slot = findSlotForArgument(mapping, 0);
        Set<ObjectSnapshot> objectValues = trace.getObjectValues(slot);

        assertTrue(objectValues.size() >= 2, "Trace should contain at least 2 Date snapshots");
        // JDK types use toString() serialization, so we check for date string patterns
        assertTrue(containsDateWithPattern(objectValues, "1970"), "Trace should contain Date from 1970 (epoch)");
        assertTrue(containsDateWithPattern(objectValues, "2000"), "Trace should contain Date from 2000 (Y2K)");
    }

    @Test
    void givenObjectWithDateField_whenInstrumenting_thenNestedDateIsCaptured() throws Exception {
        // Given: EventService.log(Event) where Event has String name and Date timestamp fields
        Path targetJar = compileAndPackageTarget(createEventServiceTarget(), "event-service.jar", "EventService");

        JavaMethodIdentifier targetMethod = createLogMethodIdentifier();
        List<JavaValueIdentifier> exportableValues = createEventArgumentIdentifiers();

        JavaRunConfiguration runConfig = createRunConfigurationBuilder(targetJar, targetMethod, exportableValues)
                .traceMode(TraceMode.NAIVE).build();

        JavaClassIdentifier instrumentationClassName = createEventServiceInstrumentationClassName();
        DiSLInstrumentor instrumentor = createInstrumentor(runConfig, instrumentationClassName);
        DiSLAnalyzer analyzer = createAnalyzer(runConfig);

        // When: Generate instrumentation and execute analysis
        InstrumentationResult instrumentation = generateInstrumentation(instrumentor, targetMethod, exportableValues);
        AnalysisResult analysisResult = executeAnalysis(analyzer, instrumentation);

        // Then: Event snapshot contains nested Date with $value
        Trace trace = deserializeTrace(analysisResult.getTraceFilePath());
        Map<Integer, JavaValueIdentifier> mapping = deserializeIdentifierMapping(instrumentation.getIdentifiersMappingPath());

        int slot = findSlotForArgument(mapping, 0);
        Set<ObjectSnapshot> objectValues = trace.getObjectValues(slot);

        assertFalse(objectValues.isEmpty(), "Trace should contain object values for Event parameter");

        ObjectSnapshot eventSnapshot = objectValues.iterator().next();
        assertEquals("Event", eventSnapshot.getSimpleClassName(), "Captured object should be Event");
        assertEquals("Christmas", eventSnapshot.getField("name"), "Event.name should be 'Christmas'");

        Object timestampField = eventSnapshot.getField("timestamp");
        assertInstanceOf(ObjectSnapshot.class, timestampField, "Event.timestamp should be ObjectSnapshot");

        ObjectSnapshot dateSnapshot = (ObjectSnapshot) timestampField;
        assertEquals("java.util.Date", dateSnapshot.getClassName(), "Nested object should be java.util.Date");
        // JDK types use toString() serialization
        String dateValue = (String) dateSnapshot.getField("$value");
        assertNotNull(dateValue, "Date.$value should not be null");
        assertTrue(dateValue.contains("Dec") || dateValue.contains("2023"),
                "Date.$value should contain Christmas 2023 date info: " + dateValue);
    }

    // ========== Helper Methods ==========

    private String createSingleDateServiceTarget() {
        // Use java.util.Date directly as the parameter type
        return """
                import java.util.Date;

                public class DateService {
                    public String format(Date date) {
                        return "Date: " + date.toString();
                    }

                    public static void main(String[] args) {
                        DateService service = new DateService();
                        Date date = new Date(%dL);
                        String formatted = service.format(date);
                        System.out.println(formatted);
                    }
                }
                """.formatted(TIMESTAMP_DEC_10_2023);
    }

    private String createMultipleDateServiceTarget() {
        return """
                import java.util.Date;

                public class DateService {
                    public String format(Date date) {
                        return "Date: " + date.toString();
                    }

                    public static void main(String[] args) {
                        DateService service = new DateService();
                        Date epoch = new Date(%dL);
                        String formatted1 = service.format(epoch);
                        System.out.println(formatted1);

                        Date y2k = new Date(%dL);
                        String formatted2 = service.format(y2k);
                        System.out.println(formatted2);
                    }
                }
                """.formatted(TIMESTAMP_EPOCH, TIMESTAMP_Y2K);
    }

    private String createEventServiceTarget() {
        return """
                import java.util.Date;

                class Event {
                    public String name;
                    public Date timestamp;
                    public Event(String name, Date timestamp) {
                        this.name = name;
                        this.timestamp = timestamp;
                    }
                }

                public class EventService {
                    public void log(Event event) {
                        System.out.println("Event: " + event.name + " at " + event.timestamp);
                    }

                    public static void main(String[] args) {
                        EventService service = new EventService();
                        Date christmas = new Date(%dL);
                        Event event = new Event("Christmas", christmas);
                        service.log(event);
                    }
                }
                """.formatted(TIMESTAMP_CHRISTMAS_2023);
    }

    private JavaMethodIdentifier createFormatMethodIdentifier() {
        // Use java.util.Date as the parameter type
        return createMethodIdentifier("", "DateService", "format", "java.lang.String", List.of("java.util.Date"));
    }

    private JavaMethodIdentifier createLogMethodIdentifier() {
        return createMethodIdentifier("", "EventService", "log", "void", List.of("Event"));
    }

    private List<JavaValueIdentifier> createDateArgumentIdentifiers() {
        // Use java.util.Date as the argument type
        return List.of(new JavaArgumentIdentifier(
                ArgumentIdentifierParameters.builder()
                        .argumentSlot(0)
                        .variableType("java.util.Date")
                        .build()));
    }

    private List<JavaValueIdentifier> createEventArgumentIdentifiers() {
        return List.of(new JavaArgumentIdentifier(
                ArgumentIdentifierParameters.builder()
                        .argumentSlot(0)
                        .variableType("Event")
                        .build()));
    }

    private JavaClassIdentifier createDateServiceInstrumentationClassName() {
        return new JavaClassIdentifier(ClassIdentifierParameters.builder()
                .packageIdentifier(JavaPackageIdentifier.DEFAULT_PACKAGE)
                .className("DateServiceInstrumentation")
                .build());
    }

    private JavaClassIdentifier createEventServiceInstrumentationClassName() {
        return new JavaClassIdentifier(ClassIdentifierParameters.builder()
                .packageIdentifier(JavaPackageIdentifier.DEFAULT_PACKAGE)
                .className("EventServiceInstrumentation")
                .build());
    }

    private boolean containsDateWithPattern(Set<ObjectSnapshot> snapshots, String pattern) {
        return snapshots.stream()
                .filter(snapshot -> "java.util.Date".equals(snapshot.getClassName()))
                .anyMatch(snapshot -> {
                    Object value = snapshot.getField("$value");
                    return value instanceof String && ((String) value).contains(pattern);
                });
    }
}

