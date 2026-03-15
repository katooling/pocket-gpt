import org.gradle.api.GradleException

plugins {
    application
    kotlin("jvm")
}

kotlin {
    sourceSets {
        main {
            kotlin.srcDir("src/commonMain/kotlin")
        }
        test {
            kotlin.srcDir("src/commonTest/kotlin")
        }
    }
}

application {
    mainClass.set("com.pocketagent.runtime.StageRunnerMainKt")
}

dependencies {
    implementation(project(":packages:core-domain"))
    implementation(project(":packages:inference-adapters"))
    implementation(project(":packages:tool-runtime"))
    implementation(project(":packages:memory"))
    implementation(project(":packages:native-bridge"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

val verifyAppRuntimeArchitecture by tasks.registering {
    group = "verification"
    description = "Fails the build when app-runtime production code references concrete inference adapters directly."

    doLast {
        val runtimeDir = file("src/commonMain/kotlin/com/pocketagent/runtime")
        if (!runtimeDir.exists()) {
            return@doLast
        }
        val offenders = fileTree(runtimeDir) {
            include("**/*.kt")
        }.files
            .filter { file -> file.readText().contains("LlamaCppInferenceModule") }
            .map { it.invariantSeparatorsPath }
            .sorted()
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "app-runtime production code must not depend on LlamaCppInferenceModule directly. Found: ${offenders.joinToString()}",
            )
        }
    }
}

tasks.named("check").configure {
    dependsOn(verifyAppRuntimeArchitecture)
}
