plugins {
    base
    id("com.android.application") version "8.7.3" apply false
    kotlin("android") version "2.0.21" apply false
    kotlin("jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

allprojects {
    group = "com.pocketagent"
    version = "0.1.0-phase0"
}
