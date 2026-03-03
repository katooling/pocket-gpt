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
    implementation(project(":packages:core-domain"))
    testImplementation(kotlin("test"))
}
