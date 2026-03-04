package com.pocketagent.android

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.ModelCatalog
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeStage2BenchmarkInstrumentationTest {
    @Test
    fun runConfiguredScenario() {
        val args = InstrumentationRegistry.getArguments()
        val scenario = (args.getString(ARG_SCENARIO) ?: "A").trim().uppercase()
        require(scenario in setOf("A", "B")) { "Unsupported scenario: $scenario" }
        val modelId = (args.getString(ARG_MODEL_ID) ?: ModelCatalog.QWEN_3_5_0_8B_Q4).trim()
        require(modelId in SUPPORTED_MODELS) { "Unsupported model id: $modelId" }

        val modelPath0_8b = requireArgument(args, ARG_MODEL_PATH_0_8B)
        val modelPath2b = requireArgument(args, ARG_MODEL_PATH_2B)
        val runs = (args.getString(ARG_RUNS)?.toIntOrNull() ?: 3).coerceAtLeast(1)
        val maxTokens = (args.getString(ARG_MAX_TOKENS)?.toIntOrNull() ?: if (scenario == "A") 128 else 256)
            .coerceAtLeast(16)

        val container = buildContainer(
            modelPath0_8b = modelPath0_8b,
            modelPath2b = modelPath2b,
        )
        container.setRoutingMode(
            when (modelId) {
                ModelCatalog.QWEN_3_5_2B_Q4 -> RoutingMode.QWEN_2B
                else -> RoutingMode.QWEN_0_8B
            },
        )

        val backend = container.runtimeBackend()
        assertEquals("Expected NATIVE_JNI backend for ENG-13 closure lane", RuntimeBackend.NATIVE_JNI, backend)

        val firstTokenSamples = mutableListOf<Long>()
        val decodeSamples = mutableListOf<Double>()
        val tokenSamples = mutableListOf<Int>()

        repeat(runs) { index ->
            val session = container.createSession()
            val streamedTokens = mutableListOf<String>()
            val response = container.sendUserMessage(
                sessionId = session,
                userText = scenarioPrompt(scenario = scenario, runIndex = index),
                taskType = if (scenario == "A") "short_text" else "long_text",
                deviceState = scenarioDeviceState(scenario),
                maxTokens = maxTokens,
                onToken = { token -> streamedTokens.add(token) },
            )

            val observedTokenCount = max(
                streamedTokens.count { it.isNotBlank() },
                response.text.split(Regex("\\s+")).count { it.isNotBlank() },
            ).coerceAtLeast(1)
            val decodeDurationMs = (response.totalLatencyMs - response.firstTokenLatencyMs).coerceAtLeast(1L)
            val decodeTps = observedTokenCount / (decodeDurationMs.toDouble() / 1000.0)

            firstTokenSamples.add(response.firstTokenLatencyMs)
            decodeSamples.add(decodeTps)
            tokenSamples.add(observedTokenCount)
        }

        val metricLine = buildMetricLine(
            backend = backend?.name ?: RuntimeBackend.UNAVAILABLE.name,
            scenario = scenario,
            modelId = modelId,
            firstTokenMs = medianLong(firstTokenSamples),
            decodeTps = medianDouble(decodeSamples),
            tokens = medianInt(tokenSamples),
            runs = runs,
            pssKb = currentPssKb(),
        )

        Log.i(METRIC_TAG, metricLine)
        println(metricLine)
    }

    private fun buildContainer(
        modelPath0_8b: String,
        modelPath2b: String,
    ): AndroidMvpContainer {
        val pathMap = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to modelPath0_8b,
            ModelCatalog.QWEN_3_5_2B_Q4 to modelPath2b,
        )
        val shaByModel = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to sha256HexFromFile(File(modelPath0_8b)),
            ModelCatalog.QWEN_3_5_2B_Q4 to sha256HexFromFile(File(modelPath2b)),
        )
        val issuerByModel = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to "internal-release",
            ModelCatalog.QWEN_3_5_2B_Q4 to "internal-release",
        )
        val signatureByModel = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to provenanceSignature(
                issuer = issuerByModel.getValue(ModelCatalog.QWEN_3_5_0_8B_Q4),
                modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
                payloadSha = shaByModel.getValue(ModelCatalog.QWEN_3_5_0_8B_Q4),
            ),
            ModelCatalog.QWEN_3_5_2B_Q4 to provenanceSignature(
                issuer = issuerByModel.getValue(ModelCatalog.QWEN_3_5_2B_Q4),
                modelId = ModelCatalog.QWEN_3_5_2B_Q4,
                payloadSha = shaByModel.getValue(ModelCatalog.QWEN_3_5_2B_Q4),
            ),
        )
        val payloadByModel = mapOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4 to "sideload:$modelPath0_8b".encodeToByteArray(),
            ModelCatalog.QWEN_3_5_2B_Q4 to "sideload:$modelPath2b".encodeToByteArray(),
        )

        return AndroidMvpContainer(
            artifactPayloadByModelId = payloadByModel,
            artifactFilePathByModelId = pathMap,
            artifactSha256ByModelId = shaByModel,
            artifactProvenanceIssuerByModelId = issuerByModel,
            artifactProvenanceSignatureByModelId = signatureByModel,
        )
    }

    private fun scenarioPrompt(scenario: String, runIndex: Int): String {
        return when (scenario) {
            "A" -> "Summarize this status update in two sentences. run=$runIndex"
            else -> "Provide a concise risk review for this rollout. ".repeat(96) + "run=$runIndex"
        }
    }

    private fun scenarioDeviceState(scenario: String): DeviceState {
        return when (scenario) {
            "A" -> DeviceState(batteryPercent = 85, thermalLevel = 3, ramClassGb = 8)
            else -> DeviceState(batteryPercent = 78, thermalLevel = 4, ramClassGb = 8)
        }
    }

    private fun currentPssKb(): Int {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        return memoryInfo.totalPss.coerceAtLeast(0)
    }

    private fun buildMetricLine(
        backend: String,
        scenario: String,
        modelId: String,
        firstTokenMs: Long,
        decodeTps: Double,
        tokens: Int,
        runs: Int,
        pssKb: Int,
    ): String {
        return buildString {
            append("STAGE2_METRIC")
            append("|backend=").append(backend)
            append("|scenario=").append(scenario)
            append("|model_id=").append(modelId)
            append("|first_token_ms=").append(firstTokenMs)
            append("|decode_tps=").append(String.format(Locale.US, "%.4f", decodeTps))
            append("|tokens=").append(tokens)
            append("|runs=").append(runs)
            append("|pss_kb=").append(pssKb)
        }
    }

    private fun medianLong(values: List<Long>): Long = values.sorted()[values.size / 2]

    private fun medianDouble(values: List<Double>): Double = values.sorted()[values.size / 2]

    private fun medianInt(values: List<Int>): Int = values.sorted()[values.size / 2]

    private fun requireArgument(args: android.os.Bundle, key: String): String {
        val value = args.getString(key)?.trim().orEmpty()
        require(value.isNotEmpty()) { "Missing instrumentation argument: $key" }
        val file = File(value)
        require(file.exists() && file.isFile) { "Model path does not exist: $value" }
        return file.absolutePath
    }

    private fun sha256HexFromFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_HASH_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun provenanceSignature(issuer: String, modelId: String, payloadSha: String): String {
        return sha256Hex("$issuer|$modelId|$payloadSha|v1".encodeToByteArray())
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val ARG_SCENARIO = "stage2_scenario"
        private const val ARG_MODEL_ID = "stage2_model_id"
        private const val ARG_MODEL_PATH_0_8B = "stage2_model_0_8b_path"
        private const val ARG_MODEL_PATH_2B = "stage2_model_2b_path"
        private const val ARG_RUNS = "stage2_runs"
        private const val ARG_MAX_TOKENS = "stage2_max_tokens"
        private const val METRIC_TAG = "STAGE2_METRIC"
        private const val DEFAULT_HASH_BUFFER_SIZE = 1024 * 1024
        private val SUPPORTED_MODELS = setOf(
            ModelCatalog.QWEN_3_5_0_8B_Q4,
            ModelCatalog.QWEN_3_5_2B_Q4,
        )
    }
}
