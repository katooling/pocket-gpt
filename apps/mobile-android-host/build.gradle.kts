plugins {
    application
    kotlin("jvm")
}

application {
    mainClass.set("com.pocketagent.runtime.StageRunnerMainKt")
}

dependencies {
    implementation(project(":packages:app-runtime"))
    implementation(project(":packages:native-bridge"))
    testImplementation(kotlin("test"))
}
