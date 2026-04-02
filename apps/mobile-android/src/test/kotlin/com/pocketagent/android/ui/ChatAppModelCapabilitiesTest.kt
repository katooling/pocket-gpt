package com.pocketagent.android.ui

import com.pocketagent.inference.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatAppModelCapabilitiesTest {
    @Test
    fun `vision capable models can attach images`() {
        assertTrue(canAttachImagesForModel(ModelCatalog.QWEN_3_5_0_8B_Q4))
        assertTrue(canAttachImagesForModel(ModelCatalog.QWEN_3_5_2B_Q4))
    }

    @Test
    fun `bonsai and null models cannot attach images`() {
        assertFalse(canAttachImagesForModel(ModelCatalog.BONSAI_8B_Q1_0_G128))
        assertFalse(canAttachImagesForModel(null))
    }
}
