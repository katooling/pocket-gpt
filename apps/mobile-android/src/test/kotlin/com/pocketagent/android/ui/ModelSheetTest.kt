package com.pocketagent.android.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelSheetTest {
    @Test
    fun `downloaded section key constant is stable`() {
        // The key-based scroll in ModelSheet relies on a stable key value.
        // This test guards against accidental renames.
        assertEquals("downloaded_section_header", DOWNLOADED_SECTION_KEY)
    }
}
