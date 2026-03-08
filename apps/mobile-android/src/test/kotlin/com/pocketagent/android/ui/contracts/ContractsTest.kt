package com.pocketagent.android.ui.contracts

import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.ToolExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContractsTest {
    @Test
    fun `chat command and event contracts preserve payload fields`() {
        val command = ChatCommand.RunTool(
            toolName = "calculator",
            jsonArgs = """{"expression":"4*9"}""",
        )
        val event = ChatEvent.ToolCompleted(
            result = ToolExecutionResult.Success("36"),
        )

        assertTrue(command is ChatCommand.RunTool)
        assertEquals("calculator", command.toolName)
        assertEquals("""{"expression":"4*9"}""", command.jsonArgs)
        assertTrue(event.result is ToolExecutionResult.Success)
    }

    @Test
    fun `provisioning command and event contracts preserve payload fields`() {
        val version = ModelDistributionVersion(
            modelId = "qwen3.5-0.8b-q4",
            version = "1",
            downloadUrl = "https://example.com/model.gguf",
            expectedSha256 = "a".repeat(64),
            provenanceIssuer = "issuer",
            provenanceSignature = "sig",
            runtimeCompatibility = "android-arm64-v8a",
            fileSizeBytes = 123L,
        )
        val command = ProvisioningCommand.QueueDownload(version)
        val event = ProvisioningEvent.Progress(
            taskId = "task-1",
            modelId = version.modelId,
            version = version.version,
            status = DownloadTaskStatus.DOWNLOADING,
            progressBytes = 50L,
            totalBytes = 100L,
        )

        assertTrue(command is ProvisioningCommand.QueueDownload)
        assertEquals(version.modelId, command.version.modelId)
        assertEquals("task-1", event.taskId)
        assertEquals(50L, event.progressBytes)
    }

    @Test
    fun `chat image completion event supports typed image result`() {
        val event = ChatEvent.ImageCompleted(
            result = ImageAnalysisResult.Success("caption"),
        )

        assertTrue(event.result is ImageAnalysisResult.Success)
        assertEquals("caption", (event.result as ImageAnalysisResult.Success).content)
    }
}
