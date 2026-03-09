package com.pocketagent.android.runtime

import android.net.Uri
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.DownloadTaskStatus
import com.pocketagent.android.runtime.modelmanager.DownloadVerificationPolicy
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelmanager.StorageSummary
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.core.ChatResponse
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ChatStreamEvent.Started
import com.pocketagent.runtime.ChatStreamEvent.TokenDelta
import com.pocketagent.runtime.ChatStreamEvent.Completed
import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.InteractionContentPart
import com.pocketagent.runtime.InteractionMessage
import com.pocketagent.runtime.InteractionRole
import com.pocketagent.runtime.MvpRuntimeFacade
import com.pocketagent.runtime.PerformanceRuntimeConfig
import com.pocketagent.runtime.RuntimePerformanceProfile
import com.pocketagent.runtime.StreamChatRequestV2
import com.pocketagent.runtime.StreamUserMessageRequest
import com.pocketagent.runtime.ToolExecutionResult
import com.pocketagent.android.testutil.fakeUri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatewayAdaptersTest {
    @Test
    fun `mvp runtime gateway delegates typed calls to facade`() = runTest {
        val facade = RecordingMvpRuntimeFacade()
        val gateway = MvpRuntimeGateway(facade)
        val sessionId = gateway.createSession()
        gateway.setRoutingMode(RoutingMode.QWEN_2B)

        val streamEvents = gateway.streamUserMessage(
            StreamUserMessageRequest(
                sessionId = sessionId,
                userText = "hello",
                taskType = "short_text",
                deviceState = com.pocketagent.inference.DeviceState(80, 3, 8),
            ),
        ).toList()
        val streamV2Events = gateway.streamChat(
            StreamChatRequestV2(
                sessionId = sessionId,
                messages = listOf(
                    InteractionMessage(
                        role = InteractionRole.USER,
                        parts = listOf(InteractionContentPart.Text("hello")),
                    ),
                ),
                taskType = "short_text",
                deviceState = com.pocketagent.inference.DeviceState(80, 3, 8),
            ),
        ).toList()
        val tool = gateway.runTool("calculator", """{"expression":"1+1"}""")
        val image = gateway.analyzeImage("/tmp/a.jpg", "describe")
        val checks = gateway.runStartupChecks()
        val diagnostics = gateway.exportDiagnostics()

        assertEquals(RoutingMode.QWEN_2B, gateway.getRoutingMode())
        assertEquals("session-1", sessionId.value)
        assertEquals(3, streamEvents.size)
        assertEquals(3, streamV2Events.size)
        assertTrue(tool is ToolExecutionResult.Success)
        assertTrue(image is ImageAnalysisResult.Success)
        assertEquals(listOf("ok"), checks)
        assertTrue(diagnostics.startsWith("diag=ok"))
        assertTrue(diagnostics.contains("GPU_OFFLOAD|runtime_supported="))
        assertEquals("calculator", facade.lastToolName)
        assertEquals("/tmp/a.jpg", facade.lastImagePath)
    }

    @Test
    fun `default provisioning gateway delegates to dependency access seam`() = runTest {
        val dependency = RecordingProvisioningDependencyAccess()
        val gateway = DefaultProvisioningGateway(dependency)
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

        assertEquals("task-1", gateway.enqueueDownload(version))
        gateway.pauseDownload("task-1")
        gateway.resumeDownload("task-1")
        gateway.retryDownload("task-1")
        gateway.cancelDownload("task-1")
        assertTrue(gateway.setActiveVersion(version.modelId, version.version))
        assertTrue(gateway.removeVersion(version.modelId, version.version))
        val imported = gateway.importModelFromUri(version.modelId, fakeUri())
        val manifest = gateway.loadModelDistributionManifest()
        val installed = gateway.listInstalledVersions(version.modelId)
        val snapshot = gateway.currentSnapshot()

        assertEquals(version.modelId, imported.modelId)
        assertEquals(1, manifest.models.size)
        assertEquals(1, installed.size)
        assertEquals(1, snapshot.models.size)
        assertEquals(1, gateway.observeDownloads().value.size)
        assertEquals("task-1", dependency.lastPausedTaskId)
        assertEquals("task-1", dependency.lastResumedTaskId)
        assertEquals("task-1", dependency.lastRetriedTaskId)
        assertEquals("task-1", dependency.lastCancelledTaskId)
    }

    @Test
    fun `mvp runtime gateway keeps gpu disabled while probe is pending`() {
        val facade = RecordingMvpRuntimeFacade()
        val gateway = MvpRuntimeGateway(
            facade = facade,
            deviceGpuOffloadSupport = DeviceGpuOffloadSupport { true },
            gpuOffloadQualifier = FakeGpuQualifier(
                resultWhenRuntimeSupported = GpuProbeResult(
                    status = GpuProbeStatus.PENDING,
                    detail = "probe_running",
                ),
            ),
        )

        assertFalse(gateway.supportsGpuOffload())
    }

    @Test
    fun `mvp runtime gateway disables gpu offload when probe fails`() {
        val facade = RecordingMvpRuntimeFacade(gpuSupported = true)
        val gateway = MvpRuntimeGateway(
            facade = facade,
            deviceGpuOffloadSupport = DeviceGpuOffloadSupport { true },
            gpuOffloadQualifier = FakeGpuQualifier(
                resultWhenRuntimeSupported = GpuProbeResult(
                    status = GpuProbeStatus.FAILED,
                    failureReason = GpuProbeFailureReason.NATIVE_LOAD_FAILED,
                ),
            ),
        )

        assertFalse(gateway.supportsGpuOffload())
    }

    @Test
    fun `mvp runtime gateway enables gpu offload when probe qualifies runtime`() {
        val facade = RecordingMvpRuntimeFacade(gpuSupported = true)
        val gateway = MvpRuntimeGateway(
            facade = facade,
            deviceGpuOffloadSupport = DeviceGpuOffloadSupport { false },
            gpuOffloadQualifier = FakeGpuQualifier(
                resultWhenRuntimeSupported = GpuProbeResult(
                    status = GpuProbeStatus.QUALIFIED,
                    maxStableGpuLayers = 8,
                ),
            ),
        )

        assertTrue(gateway.supportsGpuOffload())
        assertEquals(8, gateway.gpuOffloadStatus().maxStableGpuLayers)
    }

    @Test
    fun `mvp runtime gateway demotes qualified gpu after gpu stream failure`() = runTest {
        val qualifier = FakeGpuQualifier(
            resultWhenRuntimeSupported = GpuProbeResult(
                status = GpuProbeStatus.QUALIFIED,
                maxStableGpuLayers = 8,
            ),
        )
        val facade = RecordingMvpRuntimeFacade(
            gpuSupported = true,
            streamChatEvents = flowOf(
                Started(requestId = "req-1", startedAtEpochMs = 1L),
                ChatStreamEvent.Failed(
                    requestId = "req-1",
                    errorCode = "JNI_RUNTIME_ERROR",
                    message = "native stream failure",
                ),
            ),
        )
        val gateway = MvpRuntimeGateway(
            facade = facade,
            deviceGpuOffloadSupport = DeviceGpuOffloadSupport { true },
            gpuOffloadQualifier = qualifier,
        )

        gateway.streamChat(
            StreamChatRequestV2(
                sessionId = SessionId("session-1"),
                messages = listOf(
                    InteractionMessage(
                        role = InteractionRole.USER,
                        parts = listOf(InteractionContentPart.Text("hello")),
                    ),
                ),
                taskType = "short_text",
                deviceState = com.pocketagent.inference.DeviceState(80, 3, 8),
                performanceConfig = PerformanceRuntimeConfig.forProfile(
                    profile = RuntimePerformanceProfile.BALANCED,
                    availableCpuThreads = 4,
                    gpuEnabled = true,
                    gpuLayers = 8,
                ),
            ),
        ).toList()

        assertEquals(1, qualifier.reportedFailures.size)
        assertEquals(GpuProbeFailureReason.NATIVE_GENERATE_FAILED, qualifier.reportedFailures.single().first)
    }
}

private class FakeGpuQualifier(
    private val resultWhenRuntimeSupported: GpuProbeResult,
) : GpuOffloadQualifier {
    val reportedFailures: MutableList<Pair<GpuProbeFailureReason, String?>> = mutableListOf()

    override fun evaluate(runtimeSupported: Boolean): GpuProbeResult {
        return if (runtimeSupported) {
            resultWhenRuntimeSupported
        } else {
            GpuProbeResult(
                status = GpuProbeStatus.FAILED,
                failureReason = GpuProbeFailureReason.RUNTIME_UNSUPPORTED,
            )
        }
    }

    override fun diagnosticsLine(): String = "GPU_PROBE|status=fake"

    override fun reportRuntimeFailure(reason: GpuProbeFailureReason, detail: String?) {
        reportedFailures += reason to detail
    }
}

private class RecordingMvpRuntimeFacade(
    private val gpuSupported: Boolean = true,
    private val streamChatEvents: Flow<ChatStreamEvent>? = null,
) : MvpRuntimeFacade {
    private var currentRoutingMode: RoutingMode = RoutingMode.AUTO
    var lastToolName: String? = null
    var lastImagePath: String? = null

    override fun createSession(): SessionId = SessionId("session-1")

    override fun streamUserMessage(request: StreamUserMessageRequest): Flow<ChatStreamEvent> {
        return flowOf(
            Started(requestId = request.requestId, startedAtEpochMs = 1L),
            TokenDelta(requestId = request.requestId, token = "hi ", accumulatedText = "hi"),
            Completed(
                requestId = request.requestId,
                response = ChatResponse(
                    sessionId = request.sessionId,
                    modelId = "auto",
                    text = "ok",
                    firstTokenLatencyMs = 1L,
                    totalLatencyMs = 2L,
                ),
                finishReason = "completed",
            ),
        )
    }

    override fun streamChat(request: StreamChatRequestV2): Flow<ChatStreamEvent> {
        streamChatEvents?.let { return it }
        val latestUserText = request.messages
            .asReversed()
            .firstOrNull { message -> message.role == InteractionRole.USER }
            ?.parts
            ?.joinToString(separator = "\n") { part ->
                when (part) {
                    is InteractionContentPart.Text -> part.text
                }
            }
            .orEmpty()
        return streamUserMessage(
            StreamUserMessageRequest(
                sessionId = request.sessionId,
                userText = latestUserText,
                taskType = request.taskType,
                deviceState = request.deviceState,
                maxTokens = request.maxTokens,
                requestTimeoutMs = request.requestTimeoutMs,
                requestId = request.requestId,
                performanceConfig = request.performanceConfig,
                residencyPolicy = request.residencyPolicy,
            ),
        )
    }

    override fun cancelGeneration(sessionId: SessionId): Boolean = true

    override fun cancelGenerationByRequest(requestId: String): Boolean = true

    override fun runTool(toolName: String, jsonArgs: String): String = "legacy"

    override fun runToolDetailed(toolName: String, jsonArgs: String): ToolExecutionResult {
        lastToolName = toolName
        return ToolExecutionResult.Success("tool:$toolName")
    }

    override fun analyzeImage(imagePath: String, prompt: String): String = "legacy"

    override fun analyzeImageDetailed(imagePath: String, prompt: String): ImageAnalysisResult {
        lastImagePath = imagePath
        return ImageAnalysisResult.Success("image:$imagePath")
    }

    override fun exportDiagnostics(): String = "diag=ok"

    override fun setRoutingMode(mode: RoutingMode) {
        currentRoutingMode = mode
    }

    override fun getRoutingMode(): RoutingMode = currentRoutingMode

    override fun runStartupChecks(): List<String> = listOf("ok")

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) = Unit

    override fun deleteSession(sessionId: SessionId): Boolean = true

    override fun runtimeBackend(): String = "NATIVE_JNI"

    override fun supportsGpuOffload(): Boolean = gpuSupported
}

private class RecordingProvisioningDependencyAccess : ProvisioningDependencyAccess {
    private val downloads = MutableStateFlow(
        listOf(
            DownloadTaskState(
                taskId = "task-1",
                modelId = "qwen3.5-0.8b-q4",
                version = "1",
                downloadUrl = "https://example.com/model.gguf",
                expectedSha256 = "a".repeat(64),
                provenanceIssuer = "issuer",
                provenanceSignature = "sig",
                verificationPolicy = DownloadVerificationPolicy.INTEGRITY_ONLY,
                runtimeCompatibility = "android-arm64-v8a",
                status = DownloadTaskStatus.DOWNLOADING,
                progressBytes = 10L,
                totalBytes = 100L,
                updatedAtEpochMs = 1L,
            ),
        ),
    )

    var lastPausedTaskId: String? = null
    var lastResumedTaskId: String? = null
    var lastRetriedTaskId: String? = null
    var lastCancelledTaskId: String? = null

    override fun currentProvisioningSnapshot(): RuntimeProvisioningSnapshot {
        return RuntimeProvisioningSnapshot(
            models = listOf(
                ProvisionedModelState(
                    modelId = "qwen3.5-0.8b-q4",
                    displayName = "Qwen",
                    fileName = "qwen.gguf",
                    absolutePath = "/tmp/qwen.gguf",
                    sha256 = "a".repeat(64),
                    importedAtEpochMs = 1L,
                    activeVersion = "1",
                    installedVersions = listOf(
                        ModelVersionDescriptor(
                            modelId = "qwen3.5-0.8b-q4",
                            version = "1",
                            displayName = "Qwen",
                            absolutePath = "/tmp/qwen.gguf",
                            sha256 = "a".repeat(64),
                            provenanceIssuer = "issuer",
                            provenanceSignature = "sig",
                            runtimeCompatibility = "android-arm64-v8a",
                            fileSizeBytes = 123L,
                            importedAtEpochMs = 1L,
                            isActive = true,
                        ),
                    ),
                ),
            ),
            storageSummary = StorageSummary(
                totalBytes = 1_000L,
                freeBytes = 500L,
                usedByModelsBytes = 250L,
                tempDownloadBytes = 0L,
            ),
            requiredModelIds = setOf("qwen3.5-0.8b-q4"),
        )
    }

    override fun observeDownloads() = downloads

    override suspend fun importModelFromUri(
        modelId: String,
        sourceUri: Uri,
    ): RuntimeModelImportResult {
        return RuntimeModelImportResult(
            modelId = modelId,
            version = "1",
            absolutePath = "/tmp/model.gguf",
            sha256 = "a".repeat(64),
            copiedBytes = 123L,
            isActive = true,
        )
    }

    override suspend fun loadModelDistributionManifest(): ModelDistributionManifest {
        return ModelDistributionManifest(
            models = listOf(
                com.pocketagent.android.runtime.modelmanager.ModelDistributionModel(
                    modelId = "qwen3.5-0.8b-q4",
                    displayName = "Qwen",
                    versions = emptyList(),
                ),
            ),
        )
    }

    override fun listInstalledVersions(modelId: String): List<ModelVersionDescriptor> {
        return currentProvisioningSnapshot().models.first().installedVersions
    }

    override fun setActiveVersion(modelId: String, version: String): Boolean = true

    override fun removeVersion(modelId: String, version: String): Boolean = true

    override fun enqueueDownload(version: ModelDistributionVersion): String = "task-1"

    override fun pauseDownload(taskId: String) {
        lastPausedTaskId = taskId
    }

    override fun resumeDownload(taskId: String) {
        lastResumedTaskId = taskId
    }

    override fun retryDownload(taskId: String) {
        lastRetriedTaskId = taskId
    }

    override fun cancelDownload(taskId: String) {
        lastCancelledTaskId = taskId
    }
}
