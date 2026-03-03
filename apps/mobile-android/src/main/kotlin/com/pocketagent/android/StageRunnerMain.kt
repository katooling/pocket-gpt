package com.pocketagent.android

import com.pocketagent.inference.DeviceState

fun main() {
    val container = AndroidMvpContainer()
    val checks = container.runStartupChecks()
    if (checks.isNotEmpty()) {
        println("Startup checks failed: ${checks.joinToString(", ")}")
        return
    }

    val session = container.createSession()
    val guards = ResilienceGuards()
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
