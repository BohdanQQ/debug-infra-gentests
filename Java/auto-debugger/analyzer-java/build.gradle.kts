plugins {
    id("java-library")
    alias(libs.plugins.lombok)
}

group = "cz.cuni.mff.d3s"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(mapOf("path" to ":model-common")))
    implementation(project(mapOf("path" to ":model-java")))
    implementation(project(mapOf("path" to ":analyzer-common")))
    implementation(platform(libs.log4j.bom))
    implementation(libs.log4j)
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.junit.jupiter:junit-jupiter-engine")
    testImplementation(libs.mockito)
    testImplementation(project(":test-utils"))
    testImplementation(project(":instrumentor-common"))
    testImplementation(project(":instrumentor-java"))
    testImplementation(project(":test-generator-java", "shadow"))
    testImplementation(project(":test-generator-common"))
}

tasks.test {
    useJUnitPlatform()
}