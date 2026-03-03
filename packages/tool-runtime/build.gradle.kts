plugins {
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

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}
