package com.pocketagent.android.ui

import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatAppViewModelTest {

    @Test
    fun `transient shell flows can be set and cleared`() {
        val viewModel = ChatAppViewModel()
        val version = ModelDistributionVersion(
            modelId = "demo-model",
            version = "v1",
            downloadUrl = "https://example.invalid/model.bin",
            expectedSha256 = "abc123",
            provenanceIssuer = "test",
            provenanceSignature = "sig",
            runtimeCompatibility = "android",
            fileSizeBytes = 1024L,
        )

        viewModel.setSelectedModelIdForImport("demo-model")
        viewModel.setPendingGetReadyActivation("demo-model" to "v1")
        viewModel.setPendingMeteredWarningVersion(version)
        viewModel.setPendingNotificationPermissionVersion(version)
        viewModel.setPendingRoutingModeSwitch("demo-model" to "v1")
        viewModel.setLastDownloadTransitionRefreshKey("task:completed")
        val nextSequence = viewModel.incrementReadinessRefreshSequence()

        assertEquals("demo-model", viewModel.selectedModelIdForImport.value)
        assertEquals("demo-model" to "v1", viewModel.pendingGetReadyActivation.value)
        assertEquals(version, viewModel.pendingMeteredWarningVersion.value)
        assertEquals(version, viewModel.pendingNotificationPermissionVersion.value)
        assertEquals("demo-model" to "v1", viewModel.pendingRoutingModeSwitch.value)
        assertEquals("task:completed", viewModel.lastDownloadTransitionRefreshKey.value)
        assertEquals(1L, nextSequence)
        assertEquals(1L, viewModel.readinessRefreshSequence.value)

        viewModel.setSelectedModelIdForImport(null)
        viewModel.setPendingGetReadyActivation(null)
        viewModel.setPendingMeteredWarningVersion(null)
        viewModel.setPendingNotificationPermissionVersion(null)
        viewModel.setPendingRoutingModeSwitch(null)
        viewModel.setLastDownloadTransitionRefreshKey(null)

        assertNull(viewModel.selectedModelIdForImport.value)
        assertNull(viewModel.pendingGetReadyActivation.value)
        assertNull(viewModel.pendingMeteredWarningVersion.value)
        assertNull(viewModel.pendingNotificationPermissionVersion.value)
        assertNull(viewModel.pendingRoutingModeSwitch.value)
        assertNull(viewModel.lastDownloadTransitionRefreshKey.value)
    }
}
