package com.pocketagent.runtime

import com.pocketagent.inference.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelTemplateRegistryTest {
    @Test
    fun `registry returns chatml for required runtime models`() {
        val registry = ModelTemplateRegistry()

        assertEquals(ModelTemplateProfile.CHATML, registry.templateProfileForModel(ModelCatalog.QWEN_3_5_0_8B_Q4))
        assertEquals(ModelTemplateProfile.CHATML, registry.templateProfileForModel(ModelCatalog.QWEN_3_5_2B_Q4))
    }

    @Test
    fun `registry reports template unavailable for unknown model`() {
        val registry = ModelTemplateRegistry()

        val message = registry.ensureTemplateAvailable("unknown-model")
        assertTrue(message?.contains("TEMPLATE_UNAVAILABLE") == true)
    }
}
