package com.pocketagent.android.runtime

import android.util.Log
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.MvpRuntimeFacade
import com.pocketagent.runtime.StreamChatRequestV2
import com.pocketagent.runtime.StreamUserMessageRequest
import com.pocketagent.runtime.ToolExecutionResult
import kotlinx.coroutines.flow.Flow

fun interface DeviceGpuOffloadSupport {
    fun isSupported(): Boolean

    companion object {
        val ASSUME_SUPPORTED = DeviceGpuOffloadSupport { true }
    }
}

interface RuntimeGateway {
    fun createSession(): SessionId
    fun streamUserMessage(request: StreamUserMessageRequest): Flow<ChatStreamEvent> {
        return streamChat(
            StreamChatRequestV2(
                sessionId = request.sessionId,
                messages = listOf(
                    com.pocketagent.runtime.InteractionMessage(
                        role = com.pocketagent.runtime.InteractionRole.USER,
                        parts = listOf(com.pocketagent.runtime.InteractionContentPart.Text(request.userText)),
                    ),
                ),
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
    fun streamChat(request: StreamChatRequestV2): Flow<ChatStreamEvent>
    fun cancelGeneration(sessionId: SessionId): Boolean
    fun cancelGenerationByRequest(requestId: String): Boolean
    fun runTool(toolName: String, jsonArgs: String): ToolExecutionResult
    fun analyzeImage(imagePath: String, prompt: String): ImageAnalysisResult
    fun exportDiagnostics(): String
    fun setRoutingMode(mode: RoutingMode)
    fun getRoutingMode(): RoutingMode
    fun runStartupChecks(): List<String>
    fun restoreSession(sessionId: SessionId, turns: List<Turn>)
    fun deleteSession(sessionId: SessionId): Boolean
    fun runtimeBackend(): String?
    fun supportsGpuOffload(): Boolean
    fun gpuOffloadStatus(): GpuProbeResult = if (supportsGpuOffload()) {
        GpuProbeResult(status = GpuProbeStatus.QUALIFIED, maxStableGpuLayers = 32)
    } else {
        GpuProbeResult(
            status = GpuProbeStatus.FAILED,
            failureReason = GpuProbeFailureReason.UNKNOWN,
            detail = "gpu_offload_unsupported",
        )
    }
}

class MvpRuntimeGateway(
    private val facade: MvpRuntimeFacade,
    private val deviceGpuOffloadSupport: DeviceGpuOffloadSupport = DeviceGpuOffloadSupport.ASSUME_SUPPORTED,
    private val gpuOffloadQualifier: GpuOffloadQualifier = GpuOffloadQualifier.DISABLED,
) : RuntimeGateway {
    private val tag = "RuntimeGateway"

    override fun createSession(): SessionId = facade.createSession()

    override fun streamUserMessage(request: StreamUserMessageRequest): Flow<ChatStreamEvent> {
        return facade.streamUserMessage(request)
    }

    override fun streamChat(request: StreamChatRequestV2): Flow<ChatStreamEvent> {
        return facade.streamChat(request)
    }

    override fun cancelGeneration(sessionId: SessionId): Boolean = facade.cancelGeneration(sessionId)

    override fun cancelGenerationByRequest(requestId: String): Boolean = facade.cancelGenerationByRequest(requestId)

    override fun runTool(toolName: String, jsonArgs: String): ToolExecutionResult {
        return facade.runToolDetailed(toolName = toolName, jsonArgs = jsonArgs)
    }

    override fun analyzeImage(imagePath: String, prompt: String): ImageAnalysisResult {
        return facade.analyzeImageDetailed(imagePath = imagePath, prompt = prompt)
    }

    override fun exportDiagnostics(): String {
        val runtimeSupported = runCatching { facade.supportsGpuOffload() }.getOrElse { false }
        val devicePolicySupported = runCatching { deviceGpuOffloadSupport.isSupported() }.getOrElse { false }
        val probe = runCatching { gpuOffloadQualifier.evaluate(runtimeSupported) }.getOrElse {
            GpuProbeResult(
                status = GpuProbeStatus.FAILED,
                failureReason = GpuProbeFailureReason.UNKNOWN,
                detail = "probe_evaluation_failed:${it.message ?: it::class.simpleName}",
            )
        }
        val diagnosticFooter = buildString {
            appendLine()
            append(
                "GPU_OFFLOAD|runtime_supported=$runtimeSupported|device_policy_supported=$devicePolicySupported|" +
                    "probe_status=${probe.status}|probe_layers=${probe.maxStableGpuLayers}|" +
                    "probe_reason=${probe.failureReason ?: "none"}|probe_detail=${probe.detail.orEmpty()}",
            )
            appendLine()
            append(gpuOffloadQualifier.diagnosticsLine())
        }
        return facade.exportDiagnostics() + diagnosticFooter
    }

    override fun setRoutingMode(mode: RoutingMode) {
        facade.setRoutingMode(mode)
    }

    override fun getRoutingMode(): RoutingMode = facade.getRoutingMode()

    override fun runStartupChecks(): List<String> = facade.runStartupChecks()

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        facade.restoreSession(sessionId = sessionId, turns = turns)
    }

    override fun deleteSession(sessionId: SessionId): Boolean = facade.deleteSession(sessionId)

    override fun runtimeBackend(): String? = facade.runtimeBackend()

    override fun supportsGpuOffload(): Boolean {
        val status = gpuOffloadStatus()
        return status.status == GpuProbeStatus.QUALIFIED && status.maxStableGpuLayers > 0
    }

    override fun gpuOffloadStatus(): GpuProbeResult {
        val runtimeSupported = runCatching { facade.supportsGpuOffload() }.getOrElse { false }
        val deviceReportedSupported = runCatching { deviceGpuOffloadSupport.isSupported() }
            .getOrElse { false }
        val probe = runCatching { gpuOffloadQualifier.evaluate(runtimeSupported) }.getOrElse {
            GpuProbeResult(
                status = GpuProbeStatus.FAILED,
                failureReason = GpuProbeFailureReason.UNKNOWN,
                detail = "probe_evaluation_failed:${it.message ?: it::class.simpleName}",
            )
        }
        if (runtimeSupported != deviceReportedSupported || probe.status != GpuProbeStatus.QUALIFIED) {
            safeLogInfo(
                "GPU_OFFLOAD|eligibility|runtime_supported=$runtimeSupported|device_policy_supported=$deviceReportedSupported|" +
                    "probe_status=${probe.status}|probe_layers=${probe.maxStableGpuLayers}|" +
                    "probe_reason=${probe.failureReason ?: "none"}|authoritative=runtime_plus_probe",
            )
        }
        return probe
    }

    private fun safeLogInfo(message: String) {
        runCatching { Log.i(tag, message) }
    }
}
