package com.pocketagent.inference

import com.pocketagent.core.RoutingMode

enum class ModelTier {
    BASELINE,
    FAST,
    DEBUG,
}

enum class ModelCapability {
    SHORT_TEXT,
    LONG_TEXT,
    REASONING,
    IMAGE,
}

enum class ModelRuntimeProfile {
    PROD,
    DEV_FAST,
}

data class ModelDescriptor(
    val modelId: String,
    val tier: ModelTier,
    val bridgeSupported: Boolean,
    val autoRoutingEnabled: Boolean,
    val capabilities: Set<ModelCapability>,
    val minRamGb: Int,
    val qualityRank: Int,
    val speedRank: Int,
    val fallbackPriority: Int,
    val startupCandidate: Boolean,
    val startupRequired: Boolean,
    val defaultGetReadyProfiles: Set<ModelRuntimeProfile>,
    val envKeyToken: String,
    val includeAutoRoutingMode: Boolean = false,
    val explicitRoutingModes: Set<RoutingMode> = emptySet(),
)

data class ModelLoadValidation(
    val accepted: Boolean,
    val code: String? = null,
    val detail: String? = null,
    val normalizedModelPath: String? = null,
)

object ModelCatalog {
    const val SMOKE_ECHO_120M = "smoke-echo-120m-q4"
    const val QWEN_3_5_0_8B_Q4 = "qwen3.5-0.8b-q4"
    const val QWEN_3_5_2B_Q4 = "qwen3.5-2b-q4"
    const val SMOLLM3_3B_Q4_K_M = "smollm3-3b-q4_k_m"
    const val SMOLLM3_3B_UD_IQ2_XXS = "smollm3-3b-ud-iq2_xxs"
    const val PHI_4_MINI_Q4_K_M = "phi-4-mini-instruct-q4_k_m"

    private val descriptors: List<ModelDescriptor> = listOf(
        ModelDescriptor(
            modelId = SMOKE_ECHO_120M,
            tier = ModelTier.DEBUG,
            bridgeSupported = false,
            autoRoutingEnabled = false,
            capabilities = setOf(ModelCapability.SHORT_TEXT),
            minRamGb = 1,
            qualityRank = 0,
            speedRank = 5,
            fallbackPriority = 100,
            startupCandidate = false,
            startupRequired = false,
            defaultGetReadyProfiles = emptySet(),
            envKeyToken = "SMOKE_ECHO_120M_Q4",
        ),
        ModelDescriptor(
            modelId = QWEN_3_5_0_8B_Q4,
            tier = ModelTier.BASELINE,
            bridgeSupported = true,
            autoRoutingEnabled = true,
            capabilities = setOf(
                ModelCapability.SHORT_TEXT,
                ModelCapability.LONG_TEXT,
                ModelCapability.REASONING,
                ModelCapability.IMAGE,
            ),
            minRamGb = 6,
            qualityRank = 2,
            speedRank = 2,
            fallbackPriority = 10,
            startupCandidate = true,
            startupRequired = false,
            defaultGetReadyProfiles = setOf(ModelRuntimeProfile.PROD),
            envKeyToken = "QWEN_3_5_0_8B_Q4",
            includeAutoRoutingMode = true,
            explicitRoutingModes = setOf(RoutingMode.QWEN_0_8B),
        ),
        ModelDescriptor(
            modelId = QWEN_3_5_2B_Q4,
            tier = ModelTier.BASELINE,
            bridgeSupported = true,
            autoRoutingEnabled = true,
            capabilities = setOf(
                ModelCapability.SHORT_TEXT,
                ModelCapability.LONG_TEXT,
                ModelCapability.REASONING,
                ModelCapability.IMAGE,
            ),
            minRamGb = 12,
            qualityRank = 3,
            speedRank = 1,
            fallbackPriority = 20,
            startupCandidate = true,
            startupRequired = false,
            defaultGetReadyProfiles = emptySet(),
            envKeyToken = "QWEN_3_5_2B_Q4",
            includeAutoRoutingMode = true,
            explicitRoutingModes = setOf(RoutingMode.QWEN_2B),
        ),
        ModelDescriptor(
            modelId = SMOLLM3_3B_Q4_K_M,
            tier = ModelTier.BASELINE,
            bridgeSupported = true,
            autoRoutingEnabled = true,
            capabilities = setOf(
                ModelCapability.SHORT_TEXT,
                ModelCapability.LONG_TEXT,
                ModelCapability.REASONING,
            ),
            minRamGb = 6,
            qualityRank = 4,
            speedRank = 0,
            fallbackPriority = 15,
            startupCandidate = true,
            startupRequired = false,
            defaultGetReadyProfiles = emptySet(),
            envKeyToken = "SMOLLM3_3B_Q4_K_M",
            includeAutoRoutingMode = true,
            explicitRoutingModes = setOf(RoutingMode.SMOLLM3_3B),
        ),
        ModelDescriptor(
            modelId = SMOLLM3_3B_UD_IQ2_XXS,
            tier = ModelTier.DEBUG,
            bridgeSupported = true,
            autoRoutingEnabled = false,
            capabilities = setOf(ModelCapability.SHORT_TEXT),
            minRamGb = 4,
            qualityRank = 0,
            speedRank = 3,
            fallbackPriority = 99,
            startupCandidate = false,
            startupRequired = false,
            defaultGetReadyProfiles = emptySet(),
            envKeyToken = "SMOLLM3_3B_UD_IQ2_XXS",
        ),
        ModelDescriptor(
            modelId = PHI_4_MINI_Q4_K_M,
            tier = ModelTier.BASELINE,
            bridgeSupported = true,
            autoRoutingEnabled = true,
            capabilities = setOf(
                ModelCapability.SHORT_TEXT,
                ModelCapability.LONG_TEXT,
                ModelCapability.REASONING,
            ),
            minRamGb = 8,
            qualityRank = 5,
            speedRank = -1,
            fallbackPriority = 18,
            startupCandidate = true,
            startupRequired = false,
            defaultGetReadyProfiles = emptySet(),
            envKeyToken = "PHI_4_MINI_INSTRUCT_Q4_K_M",
            includeAutoRoutingMode = true,
            explicitRoutingModes = setOf(RoutingMode.PHI_4_MINI),
        ),
    )

    private val descriptorsByModelId: Map<String, ModelDescriptor> = descriptors.associateBy { descriptor -> descriptor.modelId }
    private val descriptorsByEnvKeyToken: Map<String, ModelDescriptor> =
        descriptors.associateBy { descriptor -> descriptor.envKeyToken }
    private val modelIdByRoutingMode: Map<RoutingMode, String> = buildMap {
        descriptors.forEach { descriptor ->
            descriptor.explicitRoutingModes.forEach { routingMode ->
                val previous = put(routingMode, descriptor.modelId)
                check(previous == null) {
                    "Duplicate explicit routing mode binding: $routingMode -> $previous and ${descriptor.modelId}"
                }
            }
        }
    }

    fun baselineModels(): List<String> = listOf(
        SMOKE_ECHO_120M,
        QWEN_3_5_0_8B_Q4,
        QWEN_3_5_2B_Q4,
    )

    fun fastTierModels(): List<String> = emptyList()

    fun modelDescriptors(): List<ModelDescriptor> = descriptors

    fun descriptorFor(modelId: String): ModelDescriptor? = descriptorsByModelId[modelId]

    fun descriptorForEnvKeyToken(envKeyToken: String): ModelDescriptor? = descriptorsByEnvKeyToken[envKeyToken]

    fun modelIdForRoutingMode(mode: RoutingMode): String? = modelIdByRoutingMode[mode]

    fun routingModesForModel(modelId: String): Set<RoutingMode> {
        val descriptor = descriptorFor(modelId) ?: return emptySet()
        return buildSet {
            if (descriptor.includeAutoRoutingMode) {
                add(RoutingMode.AUTO)
            }
            addAll(descriptor.explicitRoutingModes)
        }
    }

    fun bridgeSupportedModels(): List<String> {
        return descriptors
            .filter { descriptor -> descriptor.bridgeSupported }
            .map { descriptor -> descriptor.modelId }
    }

    fun startupCandidateModels(): List<String> {
        return descriptors
            .filter { descriptor -> descriptor.startupCandidate }
            .map { descriptor -> descriptor.modelId }
    }

    fun startupRequiredModels(): List<String> {
        return descriptors
            .filter { descriptor -> descriptor.startupRequired }
            .map { descriptor -> descriptor.modelId }
    }

    fun startupMinimumReadyCount(): Int = 1

    fun defaultGetReadyModelId(profile: ModelRuntimeProfile): String? {
        val directMatch = descriptors.firstOrNull { descriptor ->
            descriptor.defaultGetReadyProfiles.contains(profile)
        }?.modelId
        if (directMatch != null) {
            return directMatch
        }
        return descriptors.firstOrNull { descriptor ->
            descriptor.defaultGetReadyProfiles.contains(ModelRuntimeProfile.PROD)
        }?.modelId
    }

    fun preferredRuntimeOrderModelIds(): List<String> {
        return descriptors
            .filter { descriptor -> descriptor.bridgeSupported }
            .sortedBy { descriptor -> descriptor.fallbackPriority }
            .map { descriptor -> descriptor.modelId }
    }

    fun supportsTask(modelId: String, taskType: String): Boolean {
        val descriptor = descriptorFor(modelId) ?: return false
        val capability = capabilityForTask(taskType)
        return descriptor.capabilities.contains(capability)
    }

    fun autoRoutingCandidates(taskType: String): List<ModelDescriptor> {
        val capability = capabilityForTask(taskType)
        return descriptors
            .filter { descriptor -> descriptor.autoRoutingEnabled && descriptor.capabilities.contains(capability) }
    }

    fun validateBridgeLoad(
        modelId: String,
        modelPath: String?,
        supportedModels: Set<String> = bridgeSupportedModels().toSet(),
    ): ModelLoadValidation {
        if (!supportedModels.contains(modelId)) {
            return ModelLoadValidation(
                accepted = false,
                code = "MODEL_UNSUPPORTED",
                detail = "modelId=$modelId",
            )
        }
        val normalizedPath = modelPath?.trim().orEmpty()
        if (normalizedPath.isBlank()) {
            return ModelLoadValidation(
                accepted = false,
                code = "MODEL_PATH_MISSING",
                detail = "modelId=$modelId",
            )
        }
        if (!normalizedPath.endsWith(".gguf", ignoreCase = true)) {
            return ModelLoadValidation(
                accepted = false,
                code = "MODEL_PATH_INVALID",
                detail = "modelId=$modelId|path=$normalizedPath",
            )
        }
        return ModelLoadValidation(
            accepted = true,
            normalizedModelPath = normalizedPath,
        )
    }

    private fun capabilityForTask(taskType: String): ModelCapability {
        return when (taskType.trim().lowercase()) {
            "long_text" -> ModelCapability.LONG_TEXT
            "reasoning" -> ModelCapability.REASONING
            "image" -> ModelCapability.IMAGE
            else -> ModelCapability.SHORT_TEXT
        }
    }
}
