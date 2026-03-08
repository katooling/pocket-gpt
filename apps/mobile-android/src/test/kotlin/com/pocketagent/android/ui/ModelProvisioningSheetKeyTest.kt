package com.pocketagent.android.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ModelProvisioningSheetKeyTest {
    @Test
    fun `download and installed keys are distinct for same model version`() {
        val modelId = "qwen3.5-0.8b-q4"
        val version = "1.0.0"

        val downloadKey = downloadVersionItemKey(modelId = modelId, version = version)
        val installedKey = installedVersionItemKey(modelId = modelId, version = version)

        assertNotEquals(downloadKey, installedKey)
        assertEquals("download:$modelId:$version", downloadKey)
        assertEquals("installed:$modelId:$version", installedKey)
    }
}
