# Defects4J Lang-1b Integration Findings

## Overview

This document captures findings from running the auto-debugger against Defects4J Lang-1b (LANG-747), a real-world bug in Apache Commons Lang where `NumberUtils.createNumber()` fails to handle 8-digit hex numbers starting with 8-F.

## Approach Used: Fat JAR

Created a "fat JAR" containing:
- Application classes (`target/classes`)
- Test classes (`target/test-classes`) 
- JUnit 4.11
- Hamcrest 1.3
- Main-Class: `org.junit.runner.JUnitCore`

```bash
./gradlew :runner:run --args="--jar lang-1b-fat.jar \
  --source src/main/java \
  --method org.apache.commons.lang3.math.NumberUtils.createNumber(String) \
  --parameters 0:String \
  --static-method \
  --trace-mode naive \
  --test-strategy trace-based-basic \
  --args org.apache.commons.lang3.math.NumberUtilsTest"
```

## Issues Encountered

### 1. Java Version Incompatibility

**Problem:** Lang-1b compiled with Java 11 (source 1.6) produced bytecode incompatible with DiSL running on Java 21.

**Error:** `ClassFormatError: constant tag 15`

**Solution:** Recompiled with `maven.compile.source=11` and `maven.compile.target=11`, then rebuilt fat JAR with Java 21.

### 2. Defects4J CLI Unavailable

**Problem:** `defects4j checkout` requires Perl dependencies not installed.

**Workaround:** Manually cloned bare repo and applied patches:
```bash
git clone project_repos/commons-lang.git lang_1_buggy
git checkout <buggy-commit>
git apply -R patches/1.test.patch
```

### 3. Compile-Error Patches Required

Applied Defects4J compile-error patches to fix test compilation:
- `test-1-16.diff`
- `locale-1-4.diff`
- `test-1-4.diff`
- `test-1-21.diff`

## Results

| Metric | Value |
|--------|-------|
| Method invocations captured | 206 |
| Test scenarios generated | 10 |
| Tests passed | 8 |
| Tests failed | 2 |

## Test Scenario Selection Issue

**Bug-triggering input `"0x80000000"` was captured but not selected.**

The NaiveTraceBasedGenerator captured the bug-triggering input (invocations 199-200) but the diversity-based selection algorithm did not include it in the final 10 test scenarios.

Selected inputs included similar hex values (`"0xABC123"`, `"0x7fffffffffffffff0"`) but not the specific value that triggers LANG-747.

**Implication:** The test generation strategy may need improvement to:
- Prioritize edge cases / boundary values
- Include inputs that caused exceptions in the original test suite
- Use bug-pattern-aware heuristics

## Classpath Approach (Recommended)

As of 2026-01-21, the tool now supports a **classpath passthrough** approach, eliminating the need to build a fat JAR. This is less invasive and works directly with compiled class directories.

### Usage

```bash
./gradlew :runner:run --args="--jar /path/to/target/classes \
  --classpath /path/to/target/test-classes:/path/to/junit-4.11.jar:/path/to/hamcrest-core-1.3.jar \
  --source /path/to/src/main/java \
  --output-dir /path/to/output \
  --method org.apache.commons.lang3.math.NumberUtils.createNumber(String) \
  --parameters 0:String \
  --static-method \
  --disl-home $DISL_HOME \
  --trace-mode naive \
  --test-strategy trace-based-basic \
  -a org.junit.runner.JUnitCore \
  -a org.apache.commons.lang3.math.NumberUtilsTest"
```

### Key Points

1. **`--jar`** can now accept a directory of compiled classes (e.g., `target/classes`) instead of a JAR file
2. **`--classpath`** accepts a colon-separated list of paths (JARs or directories) for dependencies
3. **`-a`** (or `--args`) must be used **multiple times** for each runtime argument (not space-separated)
4. The classpath is passed to `disl.py` using `-c_opts=-cp` and `-c_opts=<path>` format

### Verification Results

The classpath approach produces **identical results** to the fat JAR approach:

| Metric | Fat JAR | Classpath |
|--------|---------|-----------|
| Invocations captured | 206 | 206 ✅ |
| Test scenarios generated | 10 | 10 ✅ |
| Tests passed | 8 | 8 ✅ |
| Tests failed | 2 | 2 ✅ |

### Complete Example for Lang-1b

```bash
export DISL_HOME="/path/to/disl"
cd /path/to/auto-debugger

./gradlew :runner:run --args="--jar ~/defects4j-work/lang_1_buggy/target/classes \
  --classpath ~/defects4j-work/lang_1_buggy/target/test-classes:~/.m2/repository/junit/junit/4.11/junit-4.11.jar:~/.m2/repository/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar \
  --source ~/defects4j-work/lang_1_buggy/src/main/java \
  --output-dir ~/defects4j-work/lang_1_buggy/auto-debugger-output \
  --method org.apache.commons.lang3.math.NumberUtils.createNumber(String) \
  --parameters 0:String \
  --static-method \
  --disl-home $DISL_HOME \
  --trace-mode naive \
  --test-strategy trace-based-basic \
  -a org.junit.runner.JUnitCore \
  -a org.apache.commons.lang3.math.NumberUtilsTest"
```

## Potential Improvements

### Test Scenario Selection

- Weight selection toward inputs observed near test failures
- Include boundary values for numeric types
- Capture and replay exception-triggering inputs

