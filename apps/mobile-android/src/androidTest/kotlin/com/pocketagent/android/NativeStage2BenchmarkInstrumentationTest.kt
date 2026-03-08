package com.pocketagent.android

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketagent.core.ChatResponse
import com.pocketagent.core.RoutingMode
import com.pocketagent.inference.DeviceState
import com.pocketagent.inference.ModelCatalog
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeStage2BenchmarkInstrumentationTest {
    @Test
    fun runConfiguredScenario() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Skipping Stage-2 native benchmark instrumentation test. Set stage2_enable_benchmark=true to run benchmark suite.",
            parseBooleanArg(args, ARG_ENABLE_BENCHMARK, defaultValue = false),
        )
        val modelId = (args.getString(ARG_MODEL_ID) ?: ModelCatalog.QWEN_3_5_0_8B_Q4).trim()
        require(modelId in SUPPORTED_MODELS) { "Unsupported model id: $modelId" }
        assumeTrue(
            "Skipping Stage-2 native benchmark instrumentation test. Missing model path arguments.",
            hasRequiredModelPaths(args, modelId),
        )
        val scenario = (args.getString(ARG_SCENARIO) ?: "A").trim().uppercase()
        require(scenario in setOf("A", "B")) { "Unsupported scenario: $scenario" }
        val modelPath = requireModelPathForBenchmark(args, modelId)
        val prefixCacheEnabled = parseBooleanArg(args, ARG_PREFIX_CACHE_ENABLED, defaultValue = true)
        val prefixCacheStrict = parseBooleanArg(args, ARG_PREFIX_CACHE_STRICT, defaultValue = false)
        val runs = (args.getString(ARG_RUNS)?.toIntOrNull() ?: 3).coerceAtLeast(1)
        val minTokens = (args.getString(ARG_MIN_TOKENS)?.toIntOrNull() ?: DEFAULT_MIN_TOKENS)
            .coerceAtLeast(1)
        val warmupMaxTokens = (args.getString(ARG_WARMUP_MAX_TOKENS)?.toIntOrNull() ?: DEFAULT_WARMUP_MAX_TOKENS)
            .coerceAtLeast(0)
        val maxTokens = resolveMaxTokens(
            raw = args.getString(ARG_MAX_TOKENS),
            defaultValue = if (scenario == "A") 128 else 256,
            minTokens = minTokens,
        )

        val container = buildContainer(
            modelPathsById = mapOf(modelId to modelPath),
            prefixCacheEnabled = prefixCacheEnabled,
            prefixCacheStrict = prefixCacheStrict,
        )
        configureContainerForModel(container = container, modelId = modelId)

        val backend = container.runtimeBackend()
        assertEquals(
            "Expected NATIVE_JNI backend for ENG-13 closure lane",
            com.pocketagent.nativebridge.RuntimeBackend.NATIVE_JNI,
            backend,
        )

        // Prime model load and one short generation so measured first-token values capture steady-state latency.
        if (warmupMaxTokens > 0) {
            runWarmup(
                container = container,
                scenario = scenario,
                runIndex = -1,
                maxTokens = warmupMaxTokens,
            )
        }

        val metricLine = executeScenario(
            container = container,
            backend = backend,
            scenario = scenario,
            modelId = modelId,
            runs = runs,
            maxTokens = maxTokens,
        )

        Log.i(METRIC_TAG, metricLine)
        println(metricLine)
    }

    @Test
    fun runConfiguredModelSweep() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Skipping Stage-2 native benchmark instrumentation sweep. Set stage2_enable_benchmark=true to run benchmark suite.",
            parseBooleanArg(args, ARG_ENABLE_BENCHMARK, defaultValue = false),
        )
        val modelId = (args.getString(ARG_MODEL_ID) ?: ModelCatalog.QWEN_3_5_0_8B_Q4).trim()
        require(modelId in SUPPORTED_MODELS) { "Unsupported model id: $modelId" }
        assumeTrue(
            "Skipping Stage-2 native benchmark instrumentation sweep. Missing model path arguments.",
            hasRequiredModelPaths(args, modelId),
        )
        val scenarios = parseScenarios(args.getString(ARG_SCENARIOS))
        val modelPath = requireModelPathForBenchmark(args, modelId)
        val prefixCacheEnabled = parseBooleanArg(args, ARG_PREFIX_CACHE_ENABLED, defaultValue = true)
        val prefixCacheStrict = parseBooleanArg(args, ARG_PREFIX_CACHE_STRICT, defaultValue = false)
        val runs = (args.getString(ARG_RUNS)?.toIntOrNull() ?: 3).coerceAtLeast(1)
        val minTokens = (args.getString(ARG_MIN_TOKENS)?.toIntOrNull() ?: DEFAULT_MIN_TOKENS)
            .coerceAtLeast(1)
        val warmupMaxTokens = (args.getString(ARG_WARMUP_MAX_TOKENS)?.toIntOrNull() ?: DEFAULT_WARMUP_MAX_TOKENS)
            .coerceAtLeast(0)
        val maxTokensA = resolveMaxTokens(
            raw = args.getString(ARG_MAX_TOKENS_A) ?: args.getString(ARG_MAX_TOKENS),
            defaultValue = 128,
            minTokens = minTokens,
        )
        val maxTokensB = resolveMaxTokens(
            raw = args.getString(ARG_MAX_TOKENS_B) ?: args.getString(ARG_MAX_TOKENS),
            defaultValue = 256,
            minTokens = minTokens,
        )

        val container = buildContainer(
            modelPathsById = mapOf(modelId to modelPath),
            prefixCacheEnabled = prefixCacheEnabled,
            prefixCacheStrict = prefixCacheStrict,
        )
        configureContainerForModel(container = container, modelId = modelId)

        val backend = container.runtimeBackend()
        assertEquals(
            "Expected NATIVE_JNI backend for ENG-13 closure lane",
            com.pocketagent.nativebridge.RuntimeBackend.NATIVE_JNI,
            backend,
        )

        if (warmupMaxTokens > 0) {
            runWarmup(
                container = container,
                scenario = scenarios.first(),
                runIndex = -1,
                maxTokens = warmupMaxTokens,
            )
        }

        scenarios.forEach { scenario ->
            val maxTokens = if (scenario == "A") maxTokensA else maxTokensB
            val metricLine = executeScenario(
                container = container,
                backend = backend,
                scenario = scenario,
                modelId = modelId,
                runs = runs,
                maxTokens = maxTokens,
            )
            Log.i(METRIC_TAG, metricLine)
            println(metricLine)
        }
    }

    private fun executeScenario(
        container: AndroidMvpContainer,
        backend: com.pocketagent.nativebridge.RuntimeBackend?,
        scenario: String,
        modelId: String,
        runs: Int,
        maxTokens: Int,
    ): String {
        val firstTokenSamples = mutableListOf<Long>()
        val decodeSamples = mutableListOf<Double>()
        val tokenSamples = mutableListOf<Int>()

        repeat(runs) { index ->
            var attempt = 0
            var response: ChatResponse? = null
            var streamedTokens: MutableList<String>? = null
            while (attempt < MAX_ATTEMPTS_PER_RUN) {
                attempt += 1
                val session = container.createSession()
                val localStreamedTokens = mutableListOf<String>()
                val prompt = buildAttemptPrompt(
                    scenario = scenario,
                    runIndex = index,
                    attempt = attempt,
                )
                val outcome = runCatching {
                    container.sendUserMessage(
                        sessionId = session,
                        userText = prompt,
                        taskType = if (scenario == "A") "short_text" else "long_text",
                        deviceState = scenarioDeviceState(scenario),
                        maxTokens = maxTokens,
                        keepModelLoaded = true,
                        onToken = { token -> localStreamedTokens.add(token) },
                    )
                }
                if (outcome.isSuccess) {
                    response = outcome.getOrNull()
                    streamedTokens = localStreamedTokens
                    break
                }
                val failure = outcome.exceptionOrNull()
                if (failure?.message?.contains("Runtime returned no tokens.") == true && attempt < MAX_ATTEMPTS_PER_RUN) {
                    Log.w(
                        METRIC_TAG,
                        "Scenario $scenario run=$index attempt=$attempt produced no tokens; retrying.",
                    )
                    continue
                }
                throw failure ?: IllegalStateException("Scenario execution failed with unknown error.")
            }

            val finalResponse = requireNotNull(response) {
                "Scenario $scenario run=$index exhausted retries without a valid response."
            }
            val finalTokens = requireNotNull(streamedTokens) {
                "Scenario $scenario run=$index did not capture streamed tokens."
            }
            assertEquals(
                "Benchmark run used unexpected model. This indicates fallback rather than the requested model path.",
                modelId,
                finalResponse.modelId,
            )

            val observedTokenCount = max(
                finalTokens.count { it.isNotBlank() },
                finalResponse.text.split(Regex("\\s+")).count { it.isNotBlank() },
            ).coerceAtLeast(1)
            val decodeDurationMs = (finalResponse.totalLatencyMs - finalResponse.firstTokenLatencyMs).coerceAtLeast(1L)
            val decodeTps = observedTokenCount / (decodeDurationMs.toDouble() / 1000.0)

            firstTokenSamples.add(finalResponse.firstTokenLatencyMs)
            decodeSamples.add(decodeTps)
            tokenSamples.add(observedTokenCount)
        }

        return buildMetricLine(
            backend = backend?.name ?: com.pocketagent.nativebridge.RuntimeBackend.UNAVAILABLE.name,
            scenario = scenario,
            modelId = modelId,
            firstTokenMs = medianLong(firstTokenSamples),
            decodeTps = medianDouble(decodeSamples),
            tokens = medianInt(tokenSamples),
            runs = runs,
            pssKb = currentPssKb(),
            coldFirstTokenMs = firstTokenSamples.firstOrNull() ?: 0L,
            warmFirstTokenMs = firstTokenSamples.lastOrNull() ?: 0L,
        )
    }

    private fun configureContainerForModel(container: AndroidMvpContainer, modelId: String) {
        container.setRoutingMode(
            when (modelId) {
                ModelCatalog.QWEN_3_5_2B_Q4 -> RoutingMode.QWEN_2B
                ModelCatalog.SMOLLM2_360M_INSTRUCT_Q4_K_M -> RoutingMode.SMOLLM2_360M
                ModelCatalog.SMOLLM2_135M_INSTRUCT_Q4_K_M -> RoutingMode.SMOLLM2_135M
                else -> RoutingMode.QWEN_0_8B
            },
        )
    }

    private fun resolveMaxTokens(raw: String?, defaultValue: Int, minTokens: Int): Int {
        return (raw?.toIntOrNull() ?: defaultValue).coerceAtLeast(minTokens)
    }

    private fun parseScenarios(raw: String?): List<String> {
        val scenarios = raw
            ?.split(",")
            ?.map { it.trim().uppercase() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?: listOf("A", "B")
        require(scenarios.isNotEmpty()) { "At least one scenario must be provided." }
        scenarios.forEach { scenario ->
            require(scenario in setOf("A", "B")) { "Unsupported scenario: $scenario" }
        }
        return scenarios
    }

    private fun buildAttemptPrompt(scenario: String, runIndex: Int, attempt: Int): String {
        val base = scenarioPrompt(scenario = scenario, runIndex = runIndex)
        if (attempt <= 1) {
            return base
        }
        return "$base Reply with at least one word."
    }

    private fun buildContainer(
        modelPathsById: Map<String, String>,
        prefixCacheEnabled: Boolean,
        prefixCacheStrict: Boolean,
    ): AndroidMvpContainer {
        val pathMap = modelPathsById
        val shaByModel = pathMap.mapValues { (_, modelPath) -> sha256HexFromFile(File(modelPath)) }
        val issuerByModel = pathMap.mapValues { "internal-release" }
        val signatureByModel = pathMap.mapValues { (modelId, _) ->
            provenanceSignature(
                issuer = issuerByModel.getValue(modelId),
                modelId = modelId,
                payloadSha = shaByModel.getValue(modelId),
            )
        }
        val payloadByModel = pathMap.mapValues { (_, modelPath) ->
            "sideload:$modelPath".encodeToByteArray()
        }

        return AndroidMvpContainer(
            artifactPayloadByModelId = payloadByModel,
            artifactFilePathByModelId = pathMap,
            artifactSha256ByModelId = shaByModel,
            artifactProvenanceIssuerByModelId = issuerByModel,
            artifactProvenanceSignatureByModelId = signatureByModel,
            prefixCacheEnabled = prefixCacheEnabled,
            prefixCacheStrict = prefixCacheStrict,
            responseCacheTtlSec = 0L,
            responseCacheMaxEntries = 0,
        )
    }

    private fun scenarioPrompt(scenario: String, runIndex: Int): String {
        return when (scenario) {
            "A" -> "Summarize rollout status in one sentence. run=$runIndex"
            else -> "List top rollout risks with short mitigations. run=$runIndex"
        }
    }

    private fun scenarioDeviceState(scenario: String): DeviceState {
        return when (scenario) {
            "A" -> DeviceState(batteryPercent = 85, thermalLevel = 3, ramClassGb = 8)
            else -> DeviceState(batteryPercent = 78, thermalLevel = 4, ramClassGb = 8)
        }
    }

    private fun runWarmup(
        container: AndroidMvpContainer,
        scenario: String,
        runIndex: Int,
        maxTokens: Int,
    ) {
        val session = container.createSession()
        runCatching {
            container.sendUserMessage(
                sessionId = session,
                userText = scenarioPrompt(scenario = scenario, runIndex = runIndex),
                taskType = if (scenario == "A") "short_text" else "long_text",
                deviceState = scenarioDeviceState(scenario),
                maxTokens = maxTokens,
                keepModelLoaded = true,
                onToken = {},
            )
        }.onFailure { throwable ->
            Log.w(METRIC_TAG, "Warmup generation failed; continuing with measured run: ${throwable.message}")
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
        coldFirstTokenMs: Long,
        warmFirstTokenMs: Long,
    ): String {
        val warmVsColdDelta = coldFirstTokenMs - warmFirstTokenMs
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
            append("|cold_first_token_ms=").append(coldFirstTokenMs)
            append("|warm_first_token_ms=").append(warmFirstTokenMs)
            append("|warm_vs_cold_first_token_delta_ms=").append(warmVsColdDelta)
        }
    }

    private fun parseBooleanArg(
        args: android.os.Bundle,
        key: String,
        defaultValue: Boolean,
    ): Boolean {
        val raw = args.getString(key)?.trim()?.lowercase() ?: return defaultValue
        return when (raw) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> defaultValue
        }
    }

    private fun medianLong(values: List<Long>): Long = values.sorted()[values.size / 2]

    private fun medianDouble(values: List<Double>): Double = values.sorted()[values.size / 2]

    private fun medianInt(values: List<Int>): Int = values.sorted()[values.size / 2]

    private fun requireModelPathForBenchmark(args: android.os.Bundle, modelId: String): String {
        val key = argumentKeyForModel(modelId)
        val value = args.getString(key)?.trim().orEmpty()
        require(value.isNotEmpty()) { "Missing instrumentation argument: $key" }
        val resolved = resolveModelPath(value)
        require(resolved != null) { "Model path does not exist: $value" }
        return resolved
    }

    private fun hasRequiredModelPaths(args: android.os.Bundle, modelId: String): Boolean {
        val key = argumentKeyForModel(modelId)
        val value = args.getString(key)?.trim().orEmpty()
        if (value.isEmpty()) {
            return false
        }
        return resolveModelPath(value) != null
    }

    private fun argumentKeyForModel(modelId: String): String {
        return when (modelId) {
            ModelCatalog.QWEN_3_5_0_8B_Q4 -> ARG_MODEL_PATH_0_8B
            ModelCatalog.QWEN_3_5_2B_Q4 -> ARG_MODEL_PATH_2B
            ModelCatalog.SMOLLM2_360M_INSTRUCT_Q4_K_M -> ARG_MODEL_PATH_SMOL_360M
            ModelCatalog.SMOLLM2_135M_INSTRUCT_Q4_K_M -> ARG_MODEL_PATH_SMOL_135M
            else -> ARG_MODEL_PATH_0_8B
        }
    }

    private fun resolveModelPath(value: String): String? {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            return null
        }
        val candidates = listOf(
            normalized,
            normalized.replace("/Android/media/", "/Download/"),
            normalized.replace("/storage/emulated/0/Android/media/", "/storage/emulated/0/Download/"),
            normalized.replace("/sdcard/Android/media/", "/sdcard/Download/"),
        )
        return candidates
            .asSequence()
            .map { candidate -> File(candidate) }
            .firstOrNull { file -> file.exists() && file.isFile }
            ?.absolutePath
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
        private const val ARG_ENABLE_BENCHMARK = "stage2_enable_benchmark"
        private const val ARG_MODEL_ID = "stage2_model_id"
        private const val ARG_MODEL_PATH_0_8B = "stage2_model_0_8b_path"
        private const val ARG_MODEL_PATH_2B = "stage2_model_2b_path"
        private const val ARG_MODEL_PATH_SMOL_360M = "stage2_model_smol_360m_path"
        private const val ARG_MODEL_PATH_SMOL_135M = "stage2_model_smol_135m_path"
        private const val ARG_RUNS = "stage2_runs"
        private const val ARG_MAX_TOKENS = "stage2_max_tokens"
        private const val ARG_MAX_TOKENS_A = "stage2_max_tokens_a"
        private const val ARG_MAX_TOKENS_B = "stage2_max_tokens_b"
        private const val ARG_SCENARIOS = "stage2_scenarios"
        private const val ARG_MIN_TOKENS = "stage2_min_tokens"
        private const val ARG_WARMUP_MAX_TOKENS = "stage2_warmup_max_tokens"
        private const val ARG_PREFIX_CACHE_ENABLED = "stage2_prefix_cache_enabled"
        private const val ARG_PREFIX_CACHE_STRICT = "stage2_prefix_cache_strict"
        private const val METRIC_TAG = "STAGE2_METRIC"
        private const val DEFAULT_HASH_BUFFER_SIZE = 1024 * 1024
        private const val DEFAULT_MIN_TOKENS = 16
        private const val DEFAULT_WARMUP_MAX_TOKENS = 8
        private const val MAX_ATTEMPTS_PER_RUN = 3
        private val SUPPORTED_MODELS = ModelCatalog.bridgeSupportedModels().toSet()
    }
}
