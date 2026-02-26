package cz.cuni.mff.d3s.autodebugger.instrumentor.java;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import javax.tools.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Compiler for DiSL instrumentation classes.
 * Handles compilation of generated DiSL Java source code into JAR files
 * with proper manifest configuration for DiSL framework integration.
 */
@Slf4j
@AllArgsConstructor
public class DiSLCompiler {
    private final String DISL_CLASS_NAME = "DiSLClass";

    private final Path jarOutputPath;
    private final Path dislClasspathRoot;
    private final List<Path> appClasspathEntries;

    public Optional<Path> compileDiSLClass(Path instrumentationSourcePath) {
        try {
            log.info("Compiling DiSL class");
            File instrumentationSourceFile = instrumentationSourcePath.toFile();
            File dislClassFile = new File(instrumentationSourceFile, DISL_CLASS_NAME + ".java");
            File collectorClassFile = new File(instrumentationSourceFile, "Collector.java");
            File collectorReClassFile = new File(instrumentationSourceFile, "CollectorRE.java");
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
                Path outputDirectory = Files.createTempDirectory("disl-classes");
                fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDirectory.toFile()));
                List<File> classPath = getDiSLClassPath(appClasspathEntries);
                fileManager.setLocation(StandardLocation.CLASS_PATH, classPath);

                // Check if DiSL dependencies are available
                boolean hasDiSLDependencies = hasDiSLDependencies(classPath);
                if (!hasDiSLDependencies) {
                    log.warn("DiSL dependencies not found. Creating stub JAR for testing purposes.");
                    return createStubInstrumentationJar();
                }

                Iterable<? extends JavaFileObject> compilationUnits =
                        fileManager.getJavaFileObjectsFromFiles(
                                List.of(dislClassFile, collectorClassFile, collectorReClassFile));

                // Create a diagnostic collector to capture compilation errors
                DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

                if (!compiler.getTask(null, fileManager, diagnostics, null, null, compilationUnits).call()) {
                    log.error("Failed to compile DiSL class");
                    System.out.println("DiSLCompiler: Compilation failed. Diagnostics:");
                    for (var diagnostic : diagnostics.getDiagnostics()) {
                        System.out.println("  " + diagnostic.toString());
                    }
                    return Optional.empty();
                }
                return packageObjects(outputDirectory);
            }
        } catch (Exception e) {
            log.error("Failed to compile DiSL class", e);
            return Optional.empty();
        }
    }

    private Optional<Path> packageObjects(Path classOutputDir) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("DiSL-Classes", DISL_CLASS_NAME);
        try {
            if (Files.exists(jarOutputPath)) {
                Files.delete(jarOutputPath);
                log.info("Removed old JAR file");
            }
            Files.createDirectories(jarOutputPath.getParent());
        } catch (IOException e) {
            log.error("Failed preparing JAR output", e);
            return Optional.empty();
        }
        try (JarOutputStream target = new JarOutputStream(new FileOutputStream(jarOutputPath.toFile()), manifest)) {
            // Walk classOutputDir and add .class files
            Files.walk(classOutputDir)
                .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".class"))
                .forEach(p -> {
                    try {
                        addToJar(p.toFile(), target);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });

            // Embed required classes from model-common (Trace class for ShadowVM)
            embedModelCommonClasses(target);
        } catch (IOException | UncheckedIOException e) {
            log.error("Failed to package DiSL class", e);
            return Optional.empty();
        }
        return Optional.of(jarOutputPath);
    }

    private void addToJar(File source, JarOutputStream target) throws IOException {
        if (source.isDirectory()) {
            throw new IOException("Directory in JAR not supported");
        }
        // Store under simple name; if we need packages later, derive relative path from output root
        String name = source.getName();
        JarEntry entry = new JarEntry(name);
        entry.setTime(source.lastModified());
        target.putNextEntry(entry);
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(source))) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                target.write(buffer, 0, count);
            }
            target.closeEntry();
        }
    }

    private List<File> getDiSLClassPath(List<Path> appClasspath) {
        List<File> classPath = appClasspath.stream().map(Path::toFile).collect(Collectors.toList());

        // Add DiSL JAR files if they exist
        addDiSLJarIfExists(classPath, "disl-server.jar");
        addDiSLJarIfExists(classPath, "dislre-server.jar");
        addDiSLJarIfExists(classPath, "dislre-dispatch.jar");

        // Add project dependencies
        addProjectJarIfExists(classPath, "../test-generator-java/build/libs/test-generator-java-1.0-SNAPSHOT-all.jar");
        addProjectJarIfExists(classPath, "../test-generator-common/build/libs/test-generator-common-1.0-SNAPSHOT.jar");
        addProjectJarIfExists(classPath, "../model-java/build/libs/model-java-1.0-SNAPSHOT.jar");
        addProjectJarIfExists(classPath, "../model-common/build/libs/model-common-1.0-SNAPSHOT.jar");

        return classPath;
    }

    private void addDiSLJarIfExists(List<File> classPath, String jarName) {
        File jarFile = new File(dislClasspathRoot.toString(), jarName);
        if (jarFile.exists()) {
            classPath.add(jarFile);
            log.debug("Added DiSL JAR to classpath: {}", jarFile.getAbsolutePath());
        } else {
            log.warn("DiSL JAR not found (this is expected in test environments): {}", jarFile.getAbsolutePath());
        }
    }

    private void addProjectJarIfExists(List<File> classPath, String jarPath) {
        File jarFile = new File(jarPath);
        if (jarFile.exists()) {
            classPath.add(jarFile);
            log.debug("Added project JAR to classpath: {}", jarFile.getAbsolutePath());
        } else {
            log.warn("Project JAR not found: {}", jarFile.getAbsolutePath());
            log.warn("Current working directory: {}", System.getProperty("user.dir"));
            // Try to find the JAR in the current working directory structure
            tryAlternativeJarPaths(classPath, jarPath);
        }
    }

    private void tryAlternativeJarPaths(List<File> classPath, String originalPath) {
        // Extract the module name and JAR name from the original path
        String jarName = new File(originalPath).getName();
        String moduleName = extractModuleName(originalPath);

        if (moduleName != null) {
            // Try different base directories
            String[] baseDirs = {".", "..", "../..", "../../.."};
            for (String baseDir : baseDirs) {
                File alternativeJar = new File(baseDir, moduleName + "/build/libs/" + jarName);
                log.debug("Trying alternative JAR path: {}", alternativeJar.getAbsolutePath());
                if (alternativeJar.exists()) {
                    classPath.add(alternativeJar);
                    log.debug("Found alternative project JAR: {}", alternativeJar.getAbsolutePath());
                    return;
                } else {
                    log.debug("Alternative JAR path does not exist: {}", alternativeJar.getAbsolutePath());
                }
            }
        }
        log.warn("No alternative JAR found for module: {} with JAR name: {}", moduleName, jarName);
    }

    private String extractModuleName(String jarPath) {
        // Extract module name from paths like "../test-generator-common/build/libs/..."
        if (jarPath.contains("/build/libs/")) {
            String beforeBuild = jarPath.substring(0, jarPath.indexOf("/build/libs/"));
            int lastSlash = beforeBuild.lastIndexOf('/');
            if (lastSlash >= 0) {
                return beforeBuild.substring(lastSlash + 1);
            } else if (beforeBuild.startsWith("../")) {
                return beforeBuild.substring(3);
            }
            return beforeBuild;
        }
        return null;
    }

    /**
     * Embeds required classes from model-common into the instrumentation JAR.
     * This ensures the Trace, IndexedTrace, and ObjectSnapshot classes are available to the ShadowVM
     * without requiring complex classpath resolution at runtime.
     */
    private void embedModelCommonClasses(JarOutputStream target) {
        // Classes to embed with their full package paths
        String[] classEntries = {
            "cz/cuni/mff/d3s/autodebugger/model/common/trace/Trace.class",
            "cz/cuni/mff/d3s/autodebugger/model/common/trace/IndexedTrace.class",
            "cz/cuni/mff/d3s/autodebugger/model/common/trace/ObjectSnapshot.class",
            "cz/cuni/mff/d3s/autodebugger/model/common/trace/JsonObjectParser.class"
        };

        // Find model-common JAR from the classpath we already resolve
        Optional<File> modelCommonJar = findModelCommonJar();
        if (modelCommonJar.isEmpty()) {
            log.warn("model-common JAR not found - Trace classes will not be embedded. " +
                     "This may cause ClassNotFoundException at runtime.");
            return;
        }

        try (JarFile jarFile = new JarFile(modelCommonJar.get())) {
            for (String classEntry : classEntries) {
                JarEntry sourceEntry = jarFile.getJarEntry(classEntry);
                if (sourceEntry == null) {
                    log.warn("{} not found in model-common JAR", classEntry);
                    continue;
                }

                // Add the class to the output JAR with its full package path
                JarEntry newEntry = new JarEntry(classEntry);
                newEntry.setTime(sourceEntry.getTime());
                target.putNextEntry(newEntry);

                try (InputStream in = jarFile.getInputStream(sourceEntry)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        target.write(buffer, 0, count);
                    }
                }
                target.closeEntry();
                log.debug("Embedded {} from model-common JAR", classEntry);
            }
        } catch (IOException e) {
            log.error("Failed to embed model-common classes", e);
        }
    }

    /**
     * Finds the model-common JAR from the classpath entries.
     */
    private Optional<File> findModelCommonJar() {
        List<File> classPath = getDiSLClassPath(appClasspathEntries);
        return classPath.stream()
                .filter(f -> f.getName().contains("model-common") && f.getName().endsWith(".jar"))
                .findFirst();
    }

    /**
     * Checks if DiSL dependencies are available in the classpath.
     */
    private boolean hasDiSLDependencies(List<File> classPath) {
        return classPath.stream()
                .anyMatch(file -> file.getName().contains("disl-server.jar") ||
                                file.getName().contains("dislre-server.jar"));
    }

    /**
     * Creates a stub instrumentation JAR for testing purposes when DiSL dependencies are not available.
     */
    private Optional<Path> createStubInstrumentationJar() {
        try {
            Files.createDirectories(jarOutputPath.getParent());
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(new Attributes.Name("DiSL-Classes"), DISL_CLASS_NAME);
            manifest.getMainAttributes().put(new Attributes.Name("Test-Stub"), "true");
            try (JarOutputStream target = new JarOutputStream(new FileOutputStream(jarOutputPath.toFile()), manifest)) {
                // Create tiny Java sources and compile them, then add resulting .class files
                Path tmp = Files.createTempDirectory("disl-stub");
                Path disl = tmp.resolve("DiSLClass.java");
                Path coll = tmp.resolve("Collector.java");
                Path colr = tmp.resolve("CollectorRE.java");
                Files.writeString(disl, "public class DiSLClass {}\n");
                Files.writeString(coll, "public class Collector {}\n");
                Files.writeString(colr, "public class CollectorRE {}\n");
                JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
                try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
                    Path out = Files.createTempDirectory("disl-stub-classes");
                    fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
                    var units = fm.getJavaFileObjectsFromFiles(List.of(disl.toFile(), coll.toFile(), colr.toFile()));
                    if (!compiler.getTask(null, fm, null, null, null, units).call()) {
                        throw new IOException("Failed to compile stub classes");
                    }
                    Files.walk(out)
                        .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".class"))
                        .forEach(p -> {
                            try { addToJar(p.toFile(), target); } catch (IOException e) { throw new UncheckedIOException(e); }
                        });
                }
            }
            log.info("Created stub instrumentation JAR: {}", jarOutputPath);
            return Optional.of(jarOutputPath);
        } catch (IOException | UncheckedIOException e) {
            log.error("Failed to create stub instrumentation JAR", e);
            return Optional.empty();
        }
    }
}
