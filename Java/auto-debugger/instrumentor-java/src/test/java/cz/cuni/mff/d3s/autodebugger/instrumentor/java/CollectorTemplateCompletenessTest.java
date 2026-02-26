package cz.cuni.mff.d3s.autodebugger.instrumentor.java;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class CollectorTemplateCompletenessTest {

    private static String readResource(String path) throws Exception {
        try (InputStream in = CollectorTemplateCompletenessTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "Resource should exist: " + path);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) { sb.append(line).append('\n'); }
                return sb.toString();
            }
        }
    }

    @Test
    void collectorRE_contains_all_registers_and_methods() throws Exception {
        String re = readResource("/templates/java/disl-analysis/CollectorRE.java");
        // registerMethod ids - uses registerMethodWithDebug wrapper which calls registerMethod
        assertTrue(re.contains("registerMethodWithDebug(\"Collector.startEvent\")"));
        assertTrue(re.contains("registerMethodWithDebug(\"Collector.collectByte\")"));
        assertTrue(re.contains("registerMethodWithDebug(\"Collector.collectChar\")"));
        assertTrue(re.contains("registerMethodWithDebug(\"Collector.collectShort\")"));
        assertTrue(re.contains("registerMethodWithDebug(\"Collector.collectInt\")"));
        assertTrue(re.contains("registerMethodWithDebug(\"Collector.collectLong\")"));
        assertTrue(re.contains("registerMethodWithDebug(\"Collector.collectFloat\")"));
        assertTrue(re.contains("registerMethodWithDebug(\"Collector.collectDouble\")"));
        assertTrue(re.contains("registerMethodWithDebug(\"Collector.collectBoolean\")"));
        assertTrue(re.contains("registerMethodWithDebug(\"Collector.collectString\")"));
        assertTrue(re.contains("registerMethodWithDebug(\"Collector.collectObject\")"));
        assertTrue(re.contains("registerMethodWithDebug(\"Collector.collectObjectJson\")"));
        // method bodies include appropriate send* calls
        assertTrue(re.contains("sendByte("));
        assertTrue(re.contains("sendChar("));
        assertTrue(re.contains("sendShort("));
        assertTrue(re.contains("sendInt("));
        assertTrue(re.contains("sendLong("));
        assertTrue(re.contains("sendFloat("));
        assertTrue(re.contains("sendDouble("));
        assertTrue(re.contains("sendBoolean("));
        assertTrue(re.contains("sendObjectPlusData("));
    }

    @Test
    void collector_contains_all_corresponding_methods() throws Exception {
        String c = readResource("/templates/java/disl-analysis/Collector.jt");
        assertTrue(c.contains("public void collectByte("));
        assertTrue(c.contains("public void collectChar("));
        assertTrue(c.contains("public void collectShort("));
        assertTrue(c.contains("public void collectInt("));
        assertTrue(c.contains("public void collectLong("));
        assertTrue(c.contains("public void collectFloat("));
        assertTrue(c.contains("public void collectDouble("));
        assertTrue(c.contains("public void collectBoolean("));
        assertTrue(c.contains("public void collectString("));
        assertTrue(c.contains("public void collectObject("));
    }
}

