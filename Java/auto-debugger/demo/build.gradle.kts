plugins {
    id("java")
}

group = "cz.cuni.mff.d3s"
version = "1.0-SNAPSHOT"

repositories { mavenCentral() }

// Ensure no external classpath is required for tiny apps
tasks.withType<JavaCompile>().configureEach {
    classpath = files()
}

tasks.register<JavaCompile>("compileArgsApp") {
    description = "Compile Args demo app"
    source = fileTree("apps/args/src") { include("**/*.java") }
    destinationDirectory.set(layout.buildDirectory.dir("classes/args"))
    options.release.set(21)
}

tasks.register<Jar>("jarArgsApp") {
    description = "Package Args demo app"
    dependsOn("compileArgsApp")
    archiveFileName.set("calc-app.jar")
    from(tasks.named<JavaCompile>("compileArgsApp").get().destinationDirectory)
    manifest.attributes(mapOf("Main-Class" to "com.example.CalcApp"))
    destinationDirectory.set(layout.projectDirectory)
}

tasks.register<JavaCompile>("compileFieldsApp") {
    description = "Compile Fields demo app"
    source = fileTree("apps/fields/src") { include("**/*.java") }
    destinationDirectory.set(layout.buildDirectory.dir("classes/fields"))
    options.release.set(21)
}

tasks.register<Jar>("jarFieldsApp") {
    description = "Package Fields demo app"
    dependsOn("compileFieldsApp")
    archiveFileName.set("fields-app.jar")
    from(tasks.named<JavaCompile>("compileFieldsApp").get().destinationDirectory)
    manifest.attributes(mapOf("Main-Class" to "com.example.FieldApp"))
    destinationDirectory.set(layout.projectDirectory)
}

tasks.register<JavaCompile>("compileStaticApp") {
    description = "Compile Static demo app"
    source = fileTree("apps/static/src") { include("**/*.java") }
    destinationDirectory.set(layout.buildDirectory.dir("classes/static"))
    options.release.set(21)
}

tasks.register<Jar>("jarStaticApp") {
    description = "Package Static demo app"
    dependsOn("compileStaticApp")
    archiveFileName.set("static-app.jar")
    from(tasks.named<JavaCompile>("compileStaticApp").get().destinationDirectory)
    manifest.attributes(mapOf("Main-Class" to "com.example.StaticApp"))
    destinationDirectory.set(layout.projectDirectory)
}

tasks.register<JavaCompile>("compileObjectsApp") {
    description = "Compile Objects demo app"
    source = fileTree("apps/objects/src") { include("**/*.java") }
    destinationDirectory.set(layout.buildDirectory.dir("classes/objects"))
    options.release.set(21)
}

tasks.register<Jar>("jarObjectsApp") {
    description = "Package Objects demo app"
    dependsOn("compileObjectsApp")
    archiveFileName.set("objects-app.jar")
    from(tasks.named<JavaCompile>("compileObjectsApp").get().destinationDirectory)
    manifest.attributes(mapOf("Main-Class" to "com.example.PersonApp"))
    destinationDirectory.set(layout.projectDirectory.dir("apps/objects"))
}

// Aggregate convenience task
tasks.register("buildDemoApps") {
    description = "Build all demo application jars"
    dependsOn("jarArgsApp", "jarFieldsApp", "jarStaticApp", "jarObjectsApp")
}

