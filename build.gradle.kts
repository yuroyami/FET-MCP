plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    id("com.gradleup.shadow") version "9.6.1"
    application
}

group = "com.yuroyami"
version = "0.1.0"

val mcpVersion = "0.15.0"
val serializationVersion = "1.11.0"
val coroutinesVersion = "1.11.0"
val kotlinxIoVersion = "0.9.1"
val slf4jVersion = "2.0.18"

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk-server:$mcpVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:$kotlinxIoVersion")
    // Logs go to stderr. Stdout is the MCP channel and must stay clean.
    implementation("org.slf4j:slf4j-simple:$slf4jVersion")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

application {
    mainClass.set("fetmcp.MainKt")
}

// Regenerates src/main/resources/fet/constraints.schema.json and the help texts from the FET sources.
// Run after every FET upgrade: ./gradlew schemagen -PfetSrc=_upstream/fet-7.10.4
tasks.register<JavaExec>("schemagen") {
    group = "fet"
    description = "Generate the constraint schema and help texts from the FET C++ sources"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("fetmcp.schemagen.SchemaGenKt")
    val fetSrc = (project.findProperty("fetSrc") as String?) ?: "_upstream/fet-7.10.4"
    args(fetSrc, "src/main/resources/fet")
}

tasks.test {
    useJUnitPlatform()
    // Tests locate the FET example corpus and an optional fet-cl binary through these.
    systemProperty("fet.examples", project.file("_upstream/fet-7.10.4/examples").absolutePath)
    systemProperty("fet.cl", System.getenv("FET_CL_PATH") ?: "")
    maxHeapSize = "2g"
    testLogging {
        events("failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.shadowJar {
    archiveBaseName.set("fet-mcp")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
}
