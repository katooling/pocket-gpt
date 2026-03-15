package com.pocketagent.android

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.streams.toList
import kotlin.test.Test
import kotlin.test.assertFalse

class ArchitectureBoundaryTest {
    @Test
    fun `ui layer does not reference app runtime dependencies singleton`() {
        val uiDir = locatePath(
            "src/main/kotlin/com/pocketagent/android/ui",
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui",
        )
        val offenders = Files.walk(uiDir).use { stream ->
            stream
                .filter { path -> path.toString().endsWith(".kt") }
                .filter { path -> String(Files.readAllBytes(path)).contains("AppRuntimeDependencies") }
                .map(Path::toString)
                .toList()
        }
        assertFalse(
            offenders.isNotEmpty(),
            "UI package must not reference AppRuntimeDependencies. Found: ${offenders.joinToString()}",
        )
    }

    @Test
    fun `app runtime dependencies singleton is only referenced by sanctioned gateway boundaries`() {
        val appDir = locatePath(
            "src/main/kotlin/com/pocketagent/android",
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android",
        )
        val allowedSuffixes = setOf(
            "com/pocketagent/android/AppDependencies.kt",
            "com/pocketagent/android/runtime/ProvisioningGateway.kt",
            "com/pocketagent/android/runtime/RuntimeBootstrapper.kt",
            "com/pocketagent/android/runtime/modelmanager/ModelDownloadCancelReceiver.kt",
        )
        val offenders = Files.walk(appDir).use { stream ->
            stream
                .filter { path -> path.toString().endsWith(".kt") }
                .filter { path -> String(Files.readAllBytes(path)).contains("AppRuntimeDependencies") }
                .map(Path::toString)
                .filter { path -> allowedSuffixes.none { suffix -> path.replace('\\', '/').endsWith(suffix) } }
                .toList()
        }
        assertFalse(
            offenders.isNotEmpty(),
            "Only sanctioned gateways may reference AppRuntimeDependencies. Found: ${offenders.joinToString()}",
        )
    }

    @Test
    fun `unit tests avoid wall clock sleep usage`() {
        val testDir = locatePath(
            "src/test/kotlin",
            "apps/mobile-android/src/test/kotlin",
        )
        val sleepRegex = Regex("""\bThread\s*\.\s*sleep\s*\(""")
        val offenders = Files.walk(testDir).use { stream ->
            stream
                .filter { path -> path.toString().endsWith(".kt") }
                .filter { path -> sleepRegex.containsMatchIn(String(Files.readAllBytes(path))) }
                .map(Path::toString)
                .toList()
        }
        assertFalse(
            offenders.isNotEmpty(),
            "Unit tests must use coroutine test time instead of wall clock sleeps. Found: ${offenders.joinToString()}",
        )
    }

    @Test
    fun `runtime package does not reference ui package types`() {
        val runtimeDir = locatePath(
            "src/main/kotlin/com/pocketagent/android/runtime",
            "apps/mobile-android/src/main/kotlin/com/pocketagent/android/runtime",
        )
        val offenders = Files.walk(runtimeDir).use { stream ->
            stream
                .filter { path -> path.toString().endsWith(".kt") }
                .filter { path -> String(Files.readAllBytes(path)).contains("com.pocketagent.android.ui") }
                .map(Path::toString)
                .toList()
        }
        assertFalse(
            offenders.isNotEmpty(),
            "Runtime package must not depend on UI packages. Found: ${offenders.joinToString()}",
        )
    }

    private fun locatePath(vararg candidates: String): Path {
        return candidates
            .asSequence()
            .map { Path.of(it) }
            .firstOrNull { it.exists() }
            ?: error("Unable to locate path from candidates: ${candidates.joinToString()}")
    }
}
