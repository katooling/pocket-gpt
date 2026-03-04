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
    testImplementation(kotlin("test"))
}
