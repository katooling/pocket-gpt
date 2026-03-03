plugins {
    application
    kotlin("jvm")
}

kotlin {
    sourceSets {
        main {
            kotlin.srcDir("../mobile-android/src/main/kotlin")
            kotlin.exclude("**/MainActivity.kt")
        }
        test {
            kotlin.srcDir("../mobile-android/src/test/kotlin")
        }
    }
}

application {
    mainClass.set("com.pocketagent.android.StageRunnerMainKt")
}

dependencies {
    implementation(project(":packages:core-domain"))
    implementation(project(":packages:inference-adapters"))
    implementation(project(":packages:tool-runtime"))
    implementation(project(":packages:memory"))
    testImplementation(kotlin("test"))
}
