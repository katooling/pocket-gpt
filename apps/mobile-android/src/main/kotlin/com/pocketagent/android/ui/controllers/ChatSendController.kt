package com.pocketagent.android.ui.controllers

import com.pocketagent.android.runtime.ChatRuntimeService
import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.ToolExecutionResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible

class ChatSendController(
    private val runtimeGateway: ChatRuntimeService,
    private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun runTool(
        toolName: String,
        jsonArgs: String,
    ): ToolExecutionResult {
        return runInterruptible(ioDispatcher) {
            runtimeGateway.runTool(toolName = toolName, jsonArgs = jsonArgs)
        }
    }

    suspend fun analyzeImage(
        imagePath: String,
        prompt: String,
    ): ImageAnalysisResult {
        return runInterruptible(ioDispatcher) {
            runtimeGateway.analyzeImage(imagePath = imagePath, prompt = prompt)
        }
    }
}
