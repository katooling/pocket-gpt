package com.pocketagent.runtime

import com.pocketagent.inference.DeviceState
import com.pocketagent.memory.FileBackedMemoryModule
import kotlin.system.exitProcess

fun main() {
    val orchestrator = RuntimeOrchestrator(
        memoryModule = FileBackedMemoryModule.defaultRuntimeModule(),
    )
    println("Runtime backend: ${orchestrator.runtimeBackend() ?: "unknown"}")
    val startupAssessor = StartupAssessor()
    val taskGuard = TaskGuard()
    val checks = orchestrator.runStartupChecks()
    val startup = startupAssessor.assessStartupChecks(checks)
    if (!startup.canProceed) {
        println("Startup checks failed: ${startup.blockingChecks.joinToString(", ")}")
        if (startup.recoverableChecks.isNotEmpty()) {
            println("Startup warnings: ${startup.recoverableChecks.joinToString(", ")}")
        }
        exitProcess(1)
    }
    if (startup.recoverableChecks.isNotEmpty()) {
        println("Startup warnings: ${startup.recoverableChecks.joinToString(", ")}")
    }

    val session = orchestrator.createSession()
    val prompt = taskGuard.validatePrompt("hello from stage1 runtime test")
    val canRun = taskGuard.canRunTask(
        taskType = "short_text",
        deviceState = DeviceState(batteryPercent = 70, thermalLevel = 3, ramClassGb = 8),
    )
    if (!canRun) {
        println("Task blocked by resilience guards.")
        exitProcess(1)
    }

    val response = orchestrator.sendUserMessage(
        sessionId = session,
        userText = prompt,
        taskType = "short_text",
        deviceState = DeviceState(batteryPercent = 70, thermalLevel = 3, ramClassGb = 8),
        maxTokens = 64,
        keepModelLoaded = false,
        onToken = {},
        requestTimeoutMs = 90_000L,
        requestId = "stage-runner-${System.currentTimeMillis()}",
        performanceConfig = PerformanceRuntimeConfig.default(),
        residencyPolicy = ModelResidencyPolicy(),
    )
    println("Model: ${response.modelId}")
    println("Response: ${response.text}")
    println("First token latency: ${response.firstTokenLatencyMs}ms")
    println("Total latency: ${response.totalLatencyMs}ms")

    val benchmark = StageBenchmarkRunner(orchestrator)
    val scenarioA = benchmark.runScenarioA()
    val scenarioB = benchmark.runScenarioB()
    val scenarioC = benchmark.runScenarioC()
    println("Scenario A: $scenarioA")
    println("Scenario B: $scenarioB")
    println("Scenario C: $scenarioC")

    println("Tool test: " + orchestrator.runTool("calculator", "{\"expression\":\"1+2\"}"))
    println("Image test: " + orchestrator.analyzeImage("photo.jpg", "What is in this image?"))
    println("Diagnostics: " + orchestrator.exportDiagnostics())
}
