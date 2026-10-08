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
    api(project(mapOf("path" to ":test-generator-common")))
    implementation(project(mapOf("path" to ":model-common")))
    implementation(project(mapOf("path" to ":model-java")))
    implementation(platform(libs.log4j.bom))
    implementation(libs.log4j)
    implementation(libs.anthropic.java)
    implementation(libs.javaparser.core)
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.junit.jupiter:junit-jupiter-engine")
    testImplementation(libs.mockito)
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
