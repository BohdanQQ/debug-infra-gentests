package cz.cuni.mff.d3s.autodebugger.runner.strategies;

import java.util.List;

/**
 * Provides available test generation strategies for the auto-debugger.
 * This class serves as a registry of all supported test generation techniques.
 */
public class TestGenerationStrategyProvider {

    /**
     * Returns all available test generation strategies.
     *
     * @return List of available test generation strategies
     */
    public static List<TestGenerationStrategy> getAvailableStrategies() {
        return List.of(
            new TestGenerationStrategy(
                "trace-based-basic",
                "Trace-Based Basic",
                "Generates unit tests based on runtime traces using basic pattern matching. " +
                "This strategy creates straightforward test cases that replicate the observed behavior.",
                true
            ),
            new TestGenerationStrategy(
                "trace-based-advanced",
                "Trace-Based Advanced",
                "Advanced trace-based test generation with temporal trace semantics and state reconstruction. " +
                "Captures the evolution of state over time for more sophisticated test scenarios.",
                false
            ),
            new TestGenerationStrategy(
                "ai-assisted",
                "AI-Assisted Generation",
                "Leverages large language models and AI techniques to generate human-readable, maintainable tests. " +
                "Combines runtime observations with AI-powered code understanding.",
                false
            )
        );
    }

    /**
     * Returns the default test generation strategy.
     *
     * @return The default strategy
     */
    public static TestGenerationStrategy getDefaultStrategy() {
        return getAvailableStrategies().stream()
            .filter(TestGenerationStrategy::isDefault)
            .findFirst()
            .orElse(getAvailableStrategies().getFirst());
    }

    /**
     * Finds a strategy by its ID.
     *
     * @param id The strategy ID to search for
     * @return The strategy with the given ID, or null if not found
     */
    public static TestGenerationStrategy getStrategyById(String id) {
        return getAvailableStrategies().stream()
            .filter(strategy -> strategy.getId().equals(id))
            .findFirst()
            .orElse(null);
    }

    /**
     * Checks if a strategy with the given ID exists.
     *
     * @param id The strategy ID to check
     * @return true if the strategy exists, false otherwise
     */
    public static boolean hasStrategy(String id) {
        return getStrategyById(id) != null;
    }
}
