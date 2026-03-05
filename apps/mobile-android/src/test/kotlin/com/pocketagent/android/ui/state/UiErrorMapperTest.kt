package com.pocketagent.android.ui.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiErrorMapperTest {
    @Test
    fun `startup mapper returns null when checks are empty`() {
        assertNull(UiErrorMapper.startupFailure(emptyList()))
    }

    @Test
    fun `startup mapper differentiates missing model guidance`() {
        val error = UiErrorMapper.startupFailure(
            listOf("Missing runtime model(s): qwen_0_8b"),
        )

        assertNotNull(error)
        assertEquals("UI-STARTUP-001", error.code)
        assertTrue(error.userMessage.contains("Download or import required models"))
    }

    @Test
    fun `startup mapper differentiates checksum mismatch guidance`() {
        val error = UiErrorMapper.startupFailure(
            listOf("Artifact verification failed: CHECKSUM_MISMATCH"),
        )

        assertNotNull(error)
        assertTrue(error.userMessage.contains("checksum mismatch"))
    }

    @Test
    fun `startup mapper differentiates provenance mismatch guidance`() {
        val error = UiErrorMapper.startupFailure(
            listOf("Artifact verification failed: PROVENANCE_SIGNATURE_MISMATCH"),
        )

        assertNotNull(error)
        assertTrue(error.userMessage.contains("provenance mismatch"))
    }

    @Test
    fun `startup mapper differentiates runtime compatibility guidance`() {
        val error = UiErrorMapper.startupFailure(
            listOf("Artifact verification failed: RUNTIME_INCOMPATIBLE"),
        )

        assertNotNull(error)
        assertTrue(error.userMessage.contains("compatibility"))
    }

    @Test
    fun `startup mapper differentiates backend readiness guidance`() {
        val error = UiErrorMapper.startupFailure(
            listOf("Runtime backend is ADB_FALLBACK but NATIVE runtime is required"),
        )

        assertNotNull(error)
        assertTrue(error.userMessage.contains("Native runtime backend is unavailable"))
    }
}
