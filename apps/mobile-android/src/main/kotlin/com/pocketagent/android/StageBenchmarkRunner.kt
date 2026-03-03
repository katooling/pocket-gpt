package com.pocketagent.android

import com.pocketagent.inference.DeviceState

data class StageBenchmarkResult(
    val scenario: String,
    val p50FirstTokenMs: Long,
    val p50DecodeTokensPerSecond: Double,
    val pass: Boolean,
)

class StageBenchmarkRunner(
    private val container: AndroidMvpContainer,
) {
    fun runScenarioA(sessionRuns: Int = 10): StageBenchmarkResult {
        val firstTokenList = mutableListOf<Long>()
        val decodeRateList = mutableListOf<Double>()
        repeat(sessionRuns) { idx ->
            val session = container.createSession()
            val response = container.sendUserMessage(
                sessionId = session,
                userText = "Short prompt run $idx",
                taskType = "short_text",
                deviceState = DeviceState(batteryPercent = 90, thermalLevel = 3, ramClassGb = 8),
                maxTokens = 128,
            )
            firstTokenList.add(response.firstTokenLatencyMs)
            decodeRateList.add(tokensPerSecond(response.text, response.totalLatencyMs))
        }
        val firstTokenMedian = medianLong(firstTokenList)
        val decodeMedian = medianDouble(decodeRateList)
        val pass = firstTokenMedian <= 2500 && decodeMedian >= 8.0
        return StageBenchmarkResult("A", firstTokenMedian, decodeMedian, pass)
    }

    fun runScenarioB(sessionRuns: Int = 10): StageBenchmarkResult {
        val firstTokenList = mutableListOf<Long>()
        val decodeRateList = mutableListOf<Double>()
        repeat(sessionRuns) { idx ->
            val session = container.createSession()
            val response = container.sendUserMessage(
                sessionId = session,
                userText = "Long prompt run $idx ".repeat(80),
                taskType = "long_text",
                deviceState = DeviceState(batteryPercent = 90, thermalLevel = 4, ramClassGb = 12),
                maxTokens = 256,
            )
            firstTokenList.add(response.firstTokenLatencyMs)
            decodeRateList.add(tokensPerSecond(response.text, response.totalLatencyMs))
        }
        val firstTokenMedian = medianLong(firstTokenList)
        val decodeMedian = medianDouble(decodeRateList)
        val pass = firstTokenMedian <= 2500 && decodeMedian >= 4.0
        return StageBenchmarkResult("B", firstTokenMedian, decodeMedian, pass)
    }

    fun runScenarioC(sessionRuns: Int = 10): StageBenchmarkResult {
        val firstTokenList = mutableListOf<Long>()
        val decodeRateList = mutableListOf<Double>()
        repeat(sessionRuns) {
            val started = System.currentTimeMillis()
            val text = container.analyzeImage(
                imagePath = "sample/document-photo.jpg",
                prompt = "Summarize the document.",
            )
            val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
            firstTokenList.add(0)
            decodeRateList.add(tokensPerSecond(text, elapsed))
        }
        val firstTokenMedian = medianLong(firstTokenList)
        val decodeMedian = medianDouble(decodeRateList)
        val pass = decodeMedian >= 4.0
        return StageBenchmarkResult("C", firstTokenMedian, decodeMedian, pass)
    }

    private fun tokensPerSecond(text: String, elapsedMs: Long): Double {
        val tokens = text.split(" ").count { it.isNotBlank() }.coerceAtLeast(1)
        val seconds = (elapsedMs.toDouble() / 1000.0).coerceAtLeast(0.001)
        return tokens / seconds
    }

    private fun medianLong(values: List<Long>): Long {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun medianDouble(values: List<Double>): Double {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
