package com.pocketagent.runtime

import com.pocketagent.core.RoutingMode
import com.pocketagent.inference.ModelCatalog
import com.pocketagent.inference.ModelRuntimeProfile
import com.pocketagent.inference.ModelTier

enum class RuntimeModelTier {
    BASELINE,
    FAST,
    DEBUG,
}

enum class StartupRequirement {
    NONE,
    OPTIONAL,
    REQUIRED,
}

data class RuntimeModelMetadata(
    val modelId: String,
    val templateProfile: ModelTemplateProfile,
    val tier: RuntimeModelTier,
    val startupRequirement: StartupRequirement = StartupRequirement.NONE,
    val defaultForGetReadyProfiles: Set<ModelRuntimeProfile> = emptySet(),
    val routingModes: Set<RoutingMode> = emptySet(),
)

data class StartupModelPolicy(
    val candidateModelIds: List<String>,
    val requiredModelIds: List<String>,
    val minimumReadyCount: Int,
)

class ModelRegistry(
    private val metadataByModelId: Map<String, RuntimeModelMetadata>,
    private val startupMinimumReadyCount: Int = 1,
) {
    fun metadataForModel(modelId: String): RuntimeModelMetadata? = metadataByModelId[modelId]

    fun allMetadata(): List<RuntimeModelMetadata> = metadataByModelId.values.sortedBy { it.modelId }

    fun templateProfilesByModelId(): Map<String, ModelTemplateProfile> {
        return metadataByModelId.mapValues { (_, metadata) -> metadata.templateProfile }
    }

    fun startupPolicy(
        profile: ModelRuntimeProfile = ModelRuntimeProfile.PROD,
    ): StartupModelPolicy {
        val startupModels = startupMetadataForProfile(profile)
        if (startupModels.isEmpty()) {
            return StartupModelPolicy(
                candidateModelIds = emptyList(),
                requiredModelIds = emptyList(),
                minimumReadyCount = 0,
            )
        }
        val candidateModelIds = startupModels.map { metadata -> metadata.modelId }
        val requiredModelIds = startupModels
            .filter { metadata -> metadata.startupRequirement == StartupRequirement.REQUIRED }
            .map { metadata -> metadata.modelId }
        val minimumReadyCount = startupMinimumReadyCount
            .coerceAtLeast(1)
            .coerceAtMost(candidateModelIds.size)
        return StartupModelPolicy(
            candidateModelIds = candidateModelIds,
            requiredModelIds = requiredModelIds,
            minimumReadyCount = minimumReadyCount,
        )
    }

    private fun startupMetadataForProfile(profile: ModelRuntimeProfile): List<RuntimeModelMetadata> {
        if (profile == ModelRuntimeProfile.DEV_FAST) {
            val fastTierMetadata = allMetadata()
                .filter { metadata ->
                    metadata.tier == RuntimeModelTier.FAST || metadata.tier == RuntimeModelTier.DEBUG
                }
            if (fastTierMetadata.isNotEmpty()) {
                return fastTierMetadata
            }
        }
        return allMetadata()
            .filter { metadata -> metadata.startupRequirement != StartupRequirement.NONE }
    }

    fun defaultGetReadyModelId(
        profile: ModelRuntimeProfile = ModelRuntimeProfile.PROD,
    ): String? {
        val directMatch = allMetadata().firstOrNull { metadata ->
            metadata.defaultForGetReadyProfiles.contains(profile)
        }?.modelId
        if (directMatch != null) {
            return directMatch
        }
        return allMetadata().firstOrNull { metadata ->
            metadata.defaultForGetReadyProfiles.contains(ModelRuntimeProfile.PROD)
        }?.modelId
    }

    companion object {
        fun default(): ModelRegistry {
            return ModelRegistry(
                metadataByModelId = defaultMetadata().associateBy { metadata -> metadata.modelId },
                startupMinimumReadyCount = 1,
            )
        }

        fun defaultMetadata(): List<RuntimeModelMetadata> {
            return ModelCatalog.modelDescriptors()
                .filter { descriptor -> descriptor.bridgeSupported || descriptor.startupCandidate }
                .map { descriptor ->
                    RuntimeModelMetadata(
                        modelId = descriptor.modelId,
                        templateProfile = when (descriptor.modelId) {
                            ModelCatalog.PHI_4_MINI_Q4_K_M -> ModelTemplateProfile.PHI
                            else -> ModelTemplateProfile.CHATML
                        },
                        tier = descriptor.tier.toRuntimeTier(),
                        startupRequirement = when {
                            descriptor.startupRequired -> StartupRequirement.REQUIRED
                            descriptor.startupCandidate -> StartupRequirement.OPTIONAL
                            else -> StartupRequirement.NONE
                        },
                        defaultForGetReadyProfiles = descriptor.defaultGetReadyProfiles,
                        routingModes = ModelCatalog.routingModesForModel(descriptor.modelId),
                    )
                }
        }
    }
}

private fun ModelTier.toRuntimeTier(): RuntimeModelTier {
    return when (this) {
        ModelTier.BASELINE -> RuntimeModelTier.BASELINE
        ModelTier.FAST -> RuntimeModelTier.FAST
        ModelTier.DEBUG -> RuntimeModelTier.DEBUG
    }
}
