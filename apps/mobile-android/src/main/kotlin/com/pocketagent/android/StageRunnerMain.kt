package com.pocketagent.android

import com.pocketagent.inference.DeviceState

fun main() {
    val container = AndroidMvpContainer()
    println("Runtime backend: ${container.runtimeBackend() ?: "unknown"}")
    val guards = ResilienceGuards()
    val checks = container.runStartupChecks()
    val startup = guards.assessStartupChecks(checks)
    if (!startup.canProceed) {
        println("Startup checks failed: ${startup.blockingChecks.joinToString(", ")}")
        if (startup.recoverableChecks.isNotEmpty()) {
            println("Startup warnings: ${startup.recoverableChecks.joinToString(", ")}")
        }
        return
    }
    if (startup.recoverableChecks.isNotEmpty()) {
        println("Startup warnings: ${startup.recoverableChecks.joinToString(", ")}")
    }

    val session = container.createSession()
    val prompt = guards.validatePrompt("hello from stage1 runtime test")
    val canRun = guards.canRunTask(
        taskType = "short_text",
        deviceState = DeviceState(batteryPercent = 70, thermalLevel = 3, ramClassGb = 8),
    )
    if (!canRun) {
        println("Task blocked by resilience guards.")
        return
    }

    val response = container.sendUserMessage(
        sessionId = session,
        userText = prompt,
        taskType = "short_text",
        deviceState = DeviceState(batteryPercent = 70, thermalLevel = 3, ramClassGb = 8),
        maxTokens = 64,
    )
    println("Model: ${response.modelId}")
    println("Response: ${response.text}")
    println("First token latency: ${response.firstTokenLatencyMs}ms")
    println("Total latency: ${response.totalLatencyMs}ms")

    val benchmark = StageBenchmarkRunner(container)
    val scenarioA = benchmark.runScenarioA()
    val scenarioB = benchmark.runScenarioB()
    val scenarioC = benchmark.runScenarioC()
    println("Scenario A: $scenarioA")
    println("Scenario B: $scenarioB")
    println("Scenario C: $scenarioC")

    println("Tool test: " + container.runTool("calculator", "{\"expression\":\"1+2\"}"))
    println("Image test: " + container.analyzeImage("photo.jpg", "What is in this image?"))
    println("Diagnostics: " + container.exportDiagnostics())
}
