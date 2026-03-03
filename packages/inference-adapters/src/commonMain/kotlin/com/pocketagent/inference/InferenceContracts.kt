package com.pocketagent.inference

data class DeviceState(
    val batteryPercent: Int,
    val thermalLevel: Int,
    val ramClassGb: Int,
)

data class InferenceRequest(
    val prompt: String,
    val maxTokens: Int,
)

interface InferenceModule {
    fun listAvailableModels(): List<String>
    fun loadModel(modelId: String): Boolean
    fun generateStream(request: InferenceRequest, onToken: (String) -> Unit)
    fun unloadModel()
}

interface RoutingModule {
    fun selectModel(taskType: String, deviceState: DeviceState): String
    fun selectContextBudget(taskType: String, deviceState: DeviceState): Int
}
