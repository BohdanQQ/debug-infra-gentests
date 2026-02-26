package cz.cuni.mff.d3s.autodebugger.runner.strategies;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestGenerationStrategyProviderTest {

    @Test
    public void givenStrategyProvider_whenGetAvailableStrategies_thenReturnsValidList() {
        List<TestGenerationStrategy> strategies = TestGenerationStrategyProvider.getAvailableStrategies();

        assertNotNull(strategies);
        assertFalse(strategies.isEmpty());
        assertEquals(3, strategies.size()); // We expect exactly 3 implemented strategies

        // Check that all strategies have required fields
        for (TestGenerationStrategy strategy : strategies) {
            assertNotNull(strategy.getId());
            assertNotNull(strategy.getDisplayName());
            assertNotNull(strategy.getDescription());
            assertFalse(strategy.getId().isEmpty());
            assertFalse(strategy.getDisplayName().isEmpty());
            assertFalse(strategy.getDescription().isEmpty());
        }
    }

    @Test
    public void givenStrategyProvider_whenGettingDefaultStrategy_thenReturnsCorrectDefault() {
        TestGenerationStrategy defaultStrategy = TestGenerationStrategyProvider.getDefaultStrategy();
        
        assertNotNull(defaultStrategy);
        assertTrue(defaultStrategy.isDefault());
        assertEquals("trace-based-basic", defaultStrategy.getId());
        assertEquals("Trace-Based Basic", defaultStrategy.getDisplayName());
    }

    @Test
    public void givenStrategyId_whenGettingStrategy_thenReturnsCorrectStrategy() {
        TestGenerationStrategy strategy = TestGenerationStrategyProvider.getStrategyById("trace-based-basic");
        
        assertNotNull(strategy);
        assertEquals("trace-based-basic", strategy.getId());
        assertEquals("Trace-Based Basic", strategy.getDisplayName());
        
        // Test non-existent strategy
        TestGenerationStrategy nonExistent = TestGenerationStrategyProvider.getStrategyById("non-existent");
        assertNull(nonExistent);
    }

    @Test
    public void givenStrategyId_whenCheckingExistence_thenReturnsCorrectResult() {
        assertTrue(TestGenerationStrategyProvider.hasStrategy("trace-based-basic"));
        assertTrue(TestGenerationStrategyProvider.hasStrategy("ai-assisted"));
        assertFalse(TestGenerationStrategyProvider.hasStrategy("non-existent"));
    }

    @Test
    public void givenTestGenerationStrategies_whenComparingEquality_thenComparesById() {
        TestGenerationStrategy strategy1 = new TestGenerationStrategy("test-id", "Test Name", "Test Description", false);
        TestGenerationStrategy strategy2 = new TestGenerationStrategy("test-id", "Different Name", "Different Description", true);
        TestGenerationStrategy strategy3 = new TestGenerationStrategy("different-id", "Test Name", "Test Description", false);
        
        assertEquals(strategy1, strategy2); // Same ID
        assertNotEquals(strategy1, strategy3); // Different ID
        assertEquals(strategy1.hashCode(), strategy2.hashCode());
        assertNotEquals(strategy1.hashCode(), strategy3.hashCode());
    }

    @Test
    public void givenStrategy_whenToString_thenReturnsDisplayName() {
        TestGenerationStrategy strategy = new TestGenerationStrategy("test-id", "Test Display Name", "Test Description", false);
        assertEquals("Test Display Name", strategy.toString());
    }

    @Test
    public void givenExpectedStrategies_whenCheckingPresence_thenAllAreAvailable() {
        List<TestGenerationStrategy> strategies = TestGenerationStrategyProvider.getAvailableStrategies();

        // Check that all implemented strategies are present
        String[] expectedIds = {
            "trace-based-basic",
            "trace-based-advanced",
            "ai-assisted"
        };

        for (String expectedId : expectedIds) {
            assertTrue(TestGenerationStrategyProvider.hasStrategy(expectedId),
                "Strategy with ID '" + expectedId + "' should be present");
        }

        // Verify that unimplemented strategies are NOT present
        String[] unimplementedIds = {
            "property-based",
            "mutation-based",
            "symbolic-execution"
        };

        for (String unimplementedId : unimplementedIds) {
            assertFalse(TestGenerationStrategyProvider.hasStrategy(unimplementedId),
                "Strategy with ID '" + unimplementedId + "' should NOT be present (not implemented)");
        }
    }

    @Test
    public void givenAvailableStrategies_whenCheckingDefaults_thenOnlyOneIsDefault() {
        List<TestGenerationStrategy> strategies = TestGenerationStrategyProvider.getAvailableStrategies();
        
        long defaultCount = strategies.stream()
            .mapToLong(strategy -> strategy.isDefault() ? 1 : 0)
            .sum();
            
        assertEquals(1, defaultCount, "There should be exactly one default strategy");
    }
}
