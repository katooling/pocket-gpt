plugins {
    base
    kotlin("jvm") version "2.0.21" apply false
}

allprojects {
    group = "com.pocketagent"
    version = "0.1.0-phase0"
}

subprojects {
    repositories {
        mavenCentral()
        google()
    }
}
