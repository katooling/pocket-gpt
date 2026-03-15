package com.pocketagent.runtime

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.streams.toList
import kotlin.test.Test
import kotlin.test.assertFalse

class ArchitectureBoundaryTest {
    @Test
    fun `app runtime production code does not reference llama cpp inference module directly`() {
        val runtimeDir = locatePath(
            "src/commonMain/kotlin/com/pocketagent/runtime",
            "packages/app-runtime/src/commonMain/kotlin/com/pocketagent/runtime",
        )
        val offenders = Files.walk(runtimeDir).use { stream ->
            stream
                .filter { path -> path.toString().endsWith(".kt") }
                .filter { path -> String(Files.readAllBytes(path)).contains("LlamaCppInferenceModule") }
                .map(Path::toString)
                .toList()
        }
        assertFalse(
            offenders.isNotEmpty(),
            "app-runtime production code must not depend on LlamaCppInferenceModule directly. Found: ${offenders.joinToString()}",
        )
    }

    private fun locatePath(vararg candidates: String): Path {
        return candidates
            .asSequence()
            .map(Path::of)
            .firstOrNull(Path::exists)
            ?: error("Unable to locate path from candidates: ${candidates.joinToString()}")
    }
}
