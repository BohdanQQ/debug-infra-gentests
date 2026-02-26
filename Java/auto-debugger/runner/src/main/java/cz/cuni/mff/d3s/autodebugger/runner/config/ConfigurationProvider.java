package cz.cuni.mff.d3s.autodebugger.runner.config;

import java.util.List;

/**
 * Provides configuration fields required by the runner module.
 * This class defines what configuration options should be available in the settings dialog.
 */
public class ConfigurationProvider {

    /**
     * Returns the list of configuration fields required by the runner.
     * This method can be extended to dynamically determine required fields
     * based on the current runtime environment or selected strategies.
     *
     * @return List of configuration fields
     */
    public static List<ConfigurationField> getRequiredConfigurationFields() {
        return List.of(
            new ConfigurationField(
                "disl.path",
                "DiSL Path",
                "Path to the DiSL (Domain-Specific Language for Instrumentation) installation directory",
                ConfigurationField.FieldType.DIRECTORY_PATH,
                true,
                ""
            ),
            new ConfigurationField(
                "anthropic.key",
                "Anthropic API Key",
                "API key for Anthropic Claude services used in LLM-based test generation",
                ConfigurationField.FieldType.PASSWORD,
                false,  // Optional since it can be provided via environment variable
                ""
            )
        );
    }

    /**
     * Returns additional optional configuration fields.
     * These fields are not required for basic operation but can enhance functionality.
     *
     * @return List of optional configuration fields
     */
    public static List<ConfigurationField> getOptionalConfigurationFields() {
        return List.of(
            new ConfigurationField(
                "output.directory",
                "Output Directory",
                "Directory where generated tests and analysis results will be stored",
                ConfigurationField.FieldType.DIRECTORY_PATH,
                false,
                System.getProperty("user.home") + "/auto-debugger-output"
            ),
            new ConfigurationField(
                "max.analysis.time",
                "Max Analysis Time (minutes)",
                "Maximum time to spend on analysis before timing out",
                ConfigurationField.FieldType.NUMBER,
                false,
                "30"
            ),
            new ConfigurationField(
                "verbose.logging",
                "Verbose Logging",
                "Enable detailed logging for debugging purposes",
                ConfigurationField.FieldType.BOOLEAN,
                false,
                "false"
            )
        );
    }

    /**
     * Returns all configuration fields (required + optional).
     *
     * @return List of all configuration fields
     */
    public static List<ConfigurationField> getAllConfigurationFields() {
        List<ConfigurationField> allFields = new java.util.ArrayList<>(getRequiredConfigurationFields());
        allFields.addAll(getOptionalConfigurationFields());
        return allFields;
    }
}
