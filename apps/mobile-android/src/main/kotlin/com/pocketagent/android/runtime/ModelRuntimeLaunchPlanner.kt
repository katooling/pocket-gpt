package com.pocketagent.android.runtime

import com.pocketagent.android.runtime.modelmanager.InstalledArtifactDescriptor
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelspec.NormalizedModelCatalogRegistry
import com.pocketagent.core.model.ModelArtifactRole
import com.pocketagent.core.model.RuntimeBackendFamilyTag
import com.pocketagent.nativebridge.ModelRuntimeFormatHint
import com.pocketagent.nativebridge.ModelRuntimeFormatProbeInput
import com.pocketagent.nativebridge.ModelRuntimeFormats
import java.io.File

data class ModelRuntimeLaunchPlan(
    val modelId: String,
    val version: String? = null,
    val formatHint: ModelRuntimeFormatHint,
    val sourceKind: String? = null,
    val promptProfileId: String? = null,
    val requiredBackendFamily: RuntimeBackendFamilyTag? = null,
    val missingRequiredArtifacts: List<String> = emptyList(),
    val multimodalProjectorPath: String? = null,
    val recommendedContextTokens: Int? = null,
    val diagnostics: List<String> = emptyList(),
) {
    val loadBlocked: Boolean
        get() = missingRequiredArtifacts.isNotEmpty()
}

interface ModelRuntimeLaunchPlanner {
    fun planInstalledModel(descriptor: ModelVersionDescriptor): ModelRuntimeLaunchPlan

    fun planDistributionVersion(version: ModelDistributionVersion): ModelRuntimeLaunchPlan
}

class DefaultModelRuntimeLaunchPlanner(
    private val catalogRegistry: NormalizedModelCatalogRegistry,
) : ModelRuntimeLaunchPlanner {
    override fun planInstalledModel(descriptor: ModelVersionDescriptor): ModelRuntimeLaunchPlan {
        val spec = catalogRegistry.specFor(descriptor.modelId)
        val variant = catalogRegistry.variantFor(descriptor.modelId, descriptor.version)
        val resolvedArtifacts = descriptor.artifacts
        val sideArtifactsByRole = resolvedArtifacts.groupBy { artifact -> artifact.role }
        val formatHint = ModelRuntimeFormats.infer(
            ModelRuntimeFormatProbeInput(
                modelId = descriptor.modelId,
                modelVersion = descriptor.version,
                modelPath = descriptor.absolutePath,
                declaredQuantization = variant?.parameters?.quantization,
            ),
        )
        val requiredArtifacts = variant?.artifactBundle?.requiredArtifacts()
            ?.filter { artifact -> artifact.role != ModelArtifactRole.PRIMARY_GGUF }
            .orEmpty()
        val missingRequiredArtifacts = requiredArtifacts
            .filter { required ->
                val matched = sideArtifactsByRole[required.role]
                    .orEmpty()
                    .firstOrNull { artifact ->
                        val explicit = artifact.absolutePath?.takeIf { it.isNotBlank() }?.let(::File)
                        explicit?.exists() == true || artifact.fileName == required.locator.fileName
                    }
                matched == null
            }
            .map { artifact -> artifact.locator.fileName ?: artifact.role.name.lowercase() }
        val projectorPath = sideArtifactsByRole[ModelArtifactRole.MMPROJ]
            .orEmpty()
            .mapNotNull { artifact -> artifact.absolutePath?.takeIf { path -> File(path).exists() } }
            .firstOrNull()
        return ModelRuntimeLaunchPlan(
            modelId = descriptor.modelId,
            version = descriptor.version,
            formatHint = formatHint,
            sourceKind = descriptor.sourceKind.name,
            promptProfileId = descriptor.promptProfileId ?: spec?.promptProfile?.profileId,
            requiredBackendFamily = when {
                variant?.source?.kind?.name == "HUGGING_FACE" && spec?.runtimeRequirements?.requiredBackendFamily != null ->
                    spec.runtimeRequirements.requiredBackendFamily
                formatHint.requiresQualifiedGpu -> RuntimeBackendFamilyTag.OPENCL
                else -> spec?.runtimeRequirements?.requiredBackendFamily
            },
            missingRequiredArtifacts = missingRequiredArtifacts,
            multimodalProjectorPath = projectorPath,
            recommendedContextTokens = variant?.parameters?.contextLength ?: spec?.runtimeRequirements?.preferredContextTokens,
            diagnostics = buildList {
                add("source=${descriptor.sourceKind.name.lowercase()}")
                formatHint.normalizedToken?.let { token -> add("format=$token") }
                if (projectorPath != null) {
                    add("mmproj=present")
                }
                if (missingRequiredArtifacts.isNotEmpty()) {
                    add("missing=${missingRequiredArtifacts.joinToString(",")}")
                }
            },
        )
    }

    override fun planDistributionVersion(version: ModelDistributionVersion): ModelRuntimeLaunchPlan {
        val spec = catalogRegistry.specFor(version.modelId)
        val variant = catalogRegistry.variantFor(version.modelId, version.version)
        val primaryArtifact = version.artifacts.first()
        return ModelRuntimeLaunchPlan(
            modelId = version.modelId,
            version = version.version,
            formatHint = ModelRuntimeFormats.infer(
                ModelRuntimeFormatProbeInput(
                    modelId = version.modelId,
                    modelVersion = version.version,
                    modelPath = primaryArtifact.fileName,
                    declaredQuantization = variant?.parameters?.quantization,
                ),
            ),
            sourceKind = version.sourceKind.name,
            promptProfileId = version.promptProfileId ?: spec?.promptProfile?.profileId,
            requiredBackendFamily = spec?.runtimeRequirements?.requiredBackendFamily,
            recommendedContextTokens = variant?.parameters?.contextLength ?: spec?.runtimeRequirements?.preferredContextTokens,
            diagnostics = listOf("source=${version.sourceKind.name.lowercase()}"),
        )
    }
}
