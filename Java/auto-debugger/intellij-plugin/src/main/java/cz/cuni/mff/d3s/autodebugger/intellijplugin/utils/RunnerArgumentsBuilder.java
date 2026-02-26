package cz.cuni.mff.d3s.autodebugger.intellijplugin.utils;

import cz.cuni.mff.d3s.autodebugger.model.common.TargetLanguage;
import cz.cuni.mff.d3s.autodebugger.runner.args.Arguments;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper to construct runner Arguments from IntelliJ-side inputs.
 */
public final class RunnerArgumentsBuilder {
    private RunnerArgumentsBuilder() {}

    public static Arguments forJava(
            String applicationJarPath,
            String sourceCodePath,
            String dislHomePath,
            String outputDirectory,
            String targetMethodReference,
            List<String> targetParameters,
            List<String> targetFields,
            List<String> runtimeArguments,
            List<String> classpathEntries,
            String testGenerationStrategy,
            String apiKey
    ) {
        var args = new Arguments();
        args.language = TargetLanguage.JAVA;
        args.applicationJarPath = applicationJarPath;
        args.sourceCodePath = sourceCodePath;
        args.dislHomePath = dislHomePath;
        args.outputDirectory = outputDirectory;
        args.targetMethodReference = targetMethodReference;
        args.targetParameters = targetParameters != null ? targetParameters : new ArrayList<>();
        args.targetFields = targetFields != null ? targetFields : new ArrayList<>();
        args.runtimeArguments = runtimeArguments != null ? runtimeArguments : new ArrayList<>();
        args.classpath = classpathEntries != null ? classpathEntries : new ArrayList<>();
        args.testGenerationStrategy = testGenerationStrategy;
        args.apiKey = apiKey;
        return args;
    }
}

