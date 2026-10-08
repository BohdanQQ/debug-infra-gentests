plugins {
    id("java-library")
    alias(libs.plugins.lombok)
    id("com.gradleup.shadow") version "8.3.5"
}

group = "cz.cuni.mff.d3s"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(mapOf("path" to ":test-runner-common")))
    implementation(project(mapOf("path" to ":model-common")))
    implementation(project(mapOf("path" to ":model-java")))
    implementation(platform(libs.log4j.bom))
    implementation(libs.log4j)

    // JUnit Platform for test execution (use BOM to align versions)
    implementation(platform(libs.junit.bom))
    implementation("org.junit.platform:junit-platform-launcher")
    implementation("org.junit.platform:junit-platform-engine")
    implementation("org.junit.jupiter:junit-jupiter-engine")
    implementation("org.junit.jupiter:junit-jupiter-params")

    // For compiling generated tests
    implementation("org.eclipse.jdt:ecj:3.35.0")

    testImplementation(libs.bundles.junit)
    testImplementation(project(mapOf("path" to ":test-generator-common")))
}

tasks.assemble {
    dependsOn(tasks.named("shadowJar"))
}

tasks.jar {
    enabled = false
}

tasks.test {
    useJUnitPlatform()
}
