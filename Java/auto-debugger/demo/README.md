# Auto-Debugger Demo Apps

This demo shows how to run the auto-debugger end-to-end against small toy Java applications.

**⚠️ Important: For full functionality, use a real DiSL installation.** The auto-debugger requires DiSL for runtime instrumentation and data collection.

## Quick Start

Build the demo apps and run (all commands from `Java/auto-debugger` directory):

```bash
# 1. Build demo apps
./gradlew -PincludeDemo :demo:buildDemoApps

# 2. Run Calculator example (captures arguments)
./gradlew :runner:run --args="--jar $(pwd)/demo/calc-app.jar --source $(pwd)/demo/apps/args/src --output-dir $(pwd)/demo/output/args --method com.example.Calculator.add(int,int) --parameters 0:int --parameters 1:int --disl-home $DISL_HOME --trace-mode naive --test-strategy trace-based-basic"

# 3. Run Counter example (captures instance fields)
./gradlew :runner:run --args="--jar $(pwd)/demo/fields-app.jar --source $(pwd)/demo/apps/fields/src --output-dir $(pwd)/demo/output/fields --method com.example.Counter.increment() --void-method --fields int:value --disl-home $DISL_HOME --trace-mode naive --test-strategy trace-based-basic"

# 4. Run Globals example (captures static fields)
./gradlew :runner:run --args="--jar $(pwd)/demo/static-app.jar --source $(pwd)/demo/apps/static/src --output-dir $(pwd)/demo/output/static --method com.example.Globals.bump() --static-method --void-method --fields static:int:X --disl-home $DISL_HOME --trace-mode naive --test-strategy trace-based-basic"

# 5. Run Person example (captures object parameters)
./gradlew :runner:run --args="--jar $(pwd)/demo/apps/objects/objects-app.jar --source $(pwd)/demo/apps/objects/src --output-dir $(pwd)/demo/output/objects --method com.example.PersonService.greet(com.example.Person) --parameters 0:com.example.Person --disl-home $DISL_HOME --trace-mode naive --test-strategy trace-based-basic"
```

Set `DISL_HOME` to your DiSL installation path (e.g., `export DISL_HOME=/path/to/disl`).

---

## Real DiSL vs Mock DiSL

### Real DiSL Installation (Recommended)
- **Functionality**: Full instrumentation, runtime data collection, and test generation
- **Requirements**: DiSL installation at `/Users/leskovde/repos/disl/output/lib` (or update `--disl-home` path)
- **Output**: Actual tests generated from captured runtime data
- **Use case**: Production use, meaningful test generation

### Mock DiSL (Limited Testing Only)
- **Functionality**: Pipeline structure testing only - no real instrumentation
- **Requirements**: Mock DiSL script and pre-populated stub results
- **Output**: Pre-written stub tests (not generated from runtime data)
- **Use case**: Testing pipeline integration without DiSL installation

Contents:
- apps/args: demonstrates argument capture (naive vs temporal)
- apps/fields: demonstrates instance fields
- apps/static: demonstrates static fields
- apps/objects: demonstrates object parameter capture
- scripts/mock-disl.py: a tiny Python script that stands in for DiSL's disl.py
- scripts/prepare_stub_results.(sh|py): creates a stub test and a results list in the output directory

Prerequisites:
- Java 21 (javac, jar)

Optional Gradle subproject
- This repo can optionally include a :demo subproject. It is disabled by default and not part of the main build.
- To include it, pass -PincludeDemo (or -DincludeDemo=true) to Gradle:

```bash
./gradlew -PincludeDemo :demo:buildDemoApps
```

This creates JARs in demo/: calc-app.jar, fields-app.jar, static-app.jar.

- Python 3 (for mock-disl.py and optional prepare script)
- macOS/Linux shell (for the .sh helper); on Windows use the Python variant

## 1) Build the demo apps

Each application is a tiny Java program that invokes one target method multiple times.
You can build them with javac + jar. For example:

```bash
# From repo root
cd demo/apps/args
rm -rf out && mkdir -p out
javac -d out $(find src -name '*.java')
jar --create --file calc-app.jar --main-class com.example.CalcApp -C out .

cd ../fields
rm -rf out && mkdir -p out
javac -d out $(find src -name '*.java')
jar --create --file fields-app.jar --main-class com.example.FieldApp -C out .

cd ../static
rm -rf out && mkdir -p out
javac -d out $(find src -name '*.java')
jar --create --file static-app.jar --main-class com.example.StaticApp -C out .
```

The resulting JARs will be placed next to each app.

## 2) Setup DiSL Installation

### Option A: Real DiSL Installation (Recommended)

Use the actual DiSL installation for full functionality:

```bash
# Use the real DiSL installation
export DISL_HOME="/Users/leskovde/repos/disl"
# Verify DiSL installation
ls -la "$DISL_HOME/output/lib"  # Should contain disl-server.jar, dislre-server.jar, etc.
ls -la "$DISL_HOME/bin/disl.py"  # Should exist and be executable
```

### Option B: Mock DiSL (Limited Testing Only)

⚠️ **Warning**: Mock DiSL only tests pipeline structure - no real instrumentation occurs.

```bash
# From repo root
cd demo/scripts
chmod +x mock-disl.py
# Create a mock DiSL home structure
export DEMO_DISL_HOME=$(pwd)/mock-disl-home
mkdir -p "$DEMO_DISL_HOME/bin" "$DEMO_DISL_HOME/output" "$DEMO_DISL_HOME/output/lib"
cp mock-disl.py "$DEMO_DISL_HOME/bin/disl.py"
chmod +x "$DEMO_DISL_HOME/bin/disl.py"
```

## 3) Pre-populate stub results (Mock DiSL only)

⚠️ **Skip this step if using real DiSL** - real DiSL will generate actual tests from runtime data.

**Only needed for Mock DiSL testing**: Create stub test and results list in the output directory.

```bash
# Using shell
./prepare_stub_results.sh /absolute/path/to/output

# Or Python
python3 prepare_stub_results.py /absolute/path/to/output
```

Notes:
- With real DiSL: Tests are generated from actual runtime instrumentation data
- With mock DiSL: Pre-written stub tests simulate the expected output structure
- The helper script writes: output/stub-tests/StubTest.java and output/generated-tests.lst

## 4) Run the auto-debugger runner

General form:

```bash
# With real DiSL installation
./gradlew :runner:run --args=" \
  --jar <app.jar> \
  --source <path-to-source> \
  --output-dir <path-to-output> \
  --method <fully.qualified.TargetClass.targetMethod(signature)> \
  --disl-home /Users/leskovde/repos/disl \
  --trace-mode <naive|temporal> \
  --test-strategy trace-based-basic \
  --classpath '' \
  --args '' \
"

# With mock DiSL (limited functionality)
./gradlew :runner:run --args=" \
  --jar <app.jar> \
  --source <path-to-source> \
  --output-dir <path-to-output> \
  --method <fully.qualified.TargetClass.targetMethod(signature)> \
  --disl-home \$DEMO_DISL_HOME \
  --trace-mode <naive|temporal> \
  --test-strategy trace-based-basic \
  --classpath '' \
  --args '' \
"
```

Examples:

### A) Args app (Calculator.add)
- Target: `com.example.Calculator.add(int,int)` called multiple times from `CalcApp`.
- Demonstrates argument export. Run in both naive and temporal modes.

```bash
# Naive mode with real DiSL
./gradlew :runner:run --args=" \
  --jar $(pwd)/demo/calc-app.jar \
  --source $(pwd)/demo/apps/args/src \
  --output-dir $(pwd)/demo/output/args-naive \
  --method com.example.Calculator.add(int,int) \
  --parameters 0:int --parameters 1:int \
  --disl-home /Users/leskovde/repos/disl \
  --trace-mode naive \
  --test-strategy trace-based-basic \
  --classpath '' \
  --args '' \
"

# Temporal mode with real DiSL
./gradlew :runner:run --args=" \
  --jar $(pwd)/demo/calc-app.jar \
  --source $(pwd)/demo/apps/args/src \
  --output-dir $(pwd)/demo/output/args-temporal \
  --method com.example.Calculator.add(int,int) \
  --parameters 0:int --parameters 1:int \
  --disl-home /Users/leskovde/repos/disl \
  --trace-mode temporal \
  --test-strategy trace-based-basic \
  --classpath '' \
  --args '' \
"
```

### B) Fields app (Counter.increment)
- Target: `com.example.Counter.increment()` called multiple times from `FieldApp`.
- Demonstrates instance field export.

```bash
./gradlew :runner:run --args=" \
  --jar $(pwd)/demo/fields-app.jar \
  --source $(pwd)/demo/apps/fields/src \
  --output-dir $(pwd)/demo/output/fields-naive \
  --method com.example.Counter.increment() \
  --void-method \
  --fields int:value \
  --disl-home /Users/leskovde/repos/disl \
  --trace-mode naive \
  --test-strategy trace-based-basic \
  --classpath '' \
  --args '' \
"
```

### C) Static fields app (Globals.bump)
- Target: `com.example.Globals.bump()` called multiple times from `StaticApp`.
- Demonstrates static field export.

```bash
./gradlew :runner:run --args=" \
  --jar $(pwd)/demo/static-app.jar \
  --source $(pwd)/demo/apps/static/src \
  --output-dir $(pwd)/demo/output/static-temporal \
  --method com.example.Globals.bump() \
  --static-method \
  --void-method \
  --fields static:int:X \
  --disl-home /Users/leskovde/repos/disl \
  --trace-mode temporal \
  --test-strategy trace-based-basic \
  --classpath '' \
  --args '' \
"
```

### D) Objects app (PersonService.greet)
- Target: `com.example.PersonService.greet(com.example.Person)` called multiple times from `PersonApp`.
- Demonstrates object parameter capture.

```bash
./gradlew :runner:run --args=" \
  --jar $(pwd)/demo/apps/objects/objects-app.jar \
  --source $(pwd)/demo/apps/objects/src \
  --output-dir $(pwd)/demo/output/objects-naive \
  --method com.example.PersonService.greet(com.example.Person) \
  --parameters 0:com.example.Person \
  --disl-home /Users/leskovde/repos/disl \
  --trace-mode naive \
  --test-strategy trace-based-basic \
  --classpath '' \
  --args '' \
"
```

## Tips and Troubleshooting

### With Real DiSL Installation:
- **No stub preparation needed** - tests are generated from actual runtime data
- **Verify DiSL installation**: Ensure `disl.py` exists and DiSL JARs are present
- **Check output**: Look for `disl-analysis-complete.marker` and `collected-values.ser` files
- **Expected behavior**: Application runs with DiSL instrumentation, data is captured, tests are generated
- **Current status**: ✅ DiSL integration working, instrumentation JAR created, DiSL servers started successfully
- **Known issue**: Test generation from DiSL RE data collection needs further configuration

### With Mock DiSL (Limited):
- **Stub preparation required** - pre-create stub results for each output directory
- **Limited functionality** - no real instrumentation occurs
- **Expected behavior** - Pipeline structure testing only

### Common Issues:
- **"DiSL dependencies not found"**: Using mock DiSL or missing DiSL installation
- **"No test files generated"**: Check DiSL installation path and permissions
- **Path issues**: Ensure all paths are absolute and accessible

## App sources
- apps/args/src/com/example/CalcApp.java
- apps/args/src/com/example/Calculator.java
- apps/fields/src/com/example/FieldApp.java
- apps/fields/src/com/example/Counter.java
- apps/static/src/com/example/StaticApp.java
- apps/static/src/com/example/Globals.java
- apps/objects/src/com/example/PersonApp.java
- apps/objects/src/com/example/PersonService.java
- apps/objects/src/com/example/Person.java

