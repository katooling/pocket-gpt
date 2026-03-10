package com.pocketagent.android.runtime.modelmanager

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelInstallIoTest {
    @Test
    fun `replaceWithAtomicMove replaces destination and removes source`() {
        val dir = Files.createTempDirectory("model-install-io-test").toFile()
        val source = dir.resolve("artifact.part").apply { writeText("new-bytes") }
        val destination = dir.resolve("artifact.gguf").apply { writeText("old-bytes") }

        val replaced = ModelInstallIo.replaceWithAtomicMove(source = source, destination = destination)

        assertTrue(replaced)
        assertFalse(source.exists())
        assertEquals("new-bytes", destination.readText())
    }
}
