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
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.45.3.0")
    testImplementation(kotlin("test"))
}
