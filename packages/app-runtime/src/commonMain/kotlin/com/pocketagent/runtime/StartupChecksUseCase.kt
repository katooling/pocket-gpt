package com.pocketagent.runtime

import com.pocketagent.core.PolicyModule
import com.pocketagent.inference.InferenceModule
import com.pocketagent.nativebridge.RuntimeBackend

internal class StartupChecksUseCase(
    private val artifactVerifier: ArtifactVerifier,
    private val interactionPlanner: InteractionPlanner,
    private val inferenceModule: InferenceModule,
    private val policyModule: PolicyModule,
    private val runtimeConfig: RuntimeConfig,
    private val networkPolicyClient: PolicyAwareNetworkClient,
    private val modelLifecycleCoordinator: ModelLifecycleCoordinator,
    private val runtimeBackendProvider: () -> String?,
    private val modelRegistry: ModelRegistry = ModelRegistry.default(),
) {
    fun run(): List<String> {
        val checks = mutableListOf<String>()
        val startupPolicy = modelRegistry.startupPolicy(profile = runtimeConfig.modelRuntimeProfile)
        val startupCandidateModels = startupPolicy.candidateModelIds
        val startupCandidateModelIdSet = startupCandidateModels.toSet()
        if (startupCandidateModels.isEmpty()) {
            checks.add("Startup model policy invalid: no startup candidate models configured.")
            return checks
        }
        val requiredModelIds = startupPolicy.requiredModelIds.toSet()
        val configuredModels = mutableSetOf<String>()
        val blockingConfigChecks = mutableListOf<String>()
        val optionalConfigWarnings = mutableListOf<String>()
        startupCandidateModels.forEach { modelId ->
            val missingFields = missingArtifactConfigFields(modelId)
            if (missingFields.isEmpty()) {
                configuredModels.add(modelId)
                return@forEach
            }
            if (requiredModelIds.contains(modelId)) {
                missingFields.forEach { field ->
                    blockingConfigChecks.add("MODEL_ARTIFACT_CONFIG_MISSING:model=$modelId;field=$field")
                }
            } else {
                optionalConfigWarnings.add(
                    "Optional runtime model unavailable: $modelId. " +
                        "MODEL_ARTIFACT_CONFIG_MISSING:model=$modelId;field=${missingFields.joinToString(",")}",
                )
            }
        }
        if (blockingConfigChecks.isNotEmpty()) {
            checks.addAll(blockingConfigChecks)
            return checks
        }
        if (configuredModels.size < startupPolicy.minimumReadyCount) {
            val fallbackConfiguredModels = fullyConfiguredRuntimeModelIds()
                .filterNot { modelId -> startupCandidateModelIdSet.contains(modelId) }
            if (fallbackConfiguredModels.isNotEmpty()) {
                configuredModels.addAll(fallbackConfiguredModels)
            }
        }
        if (configuredModels.size < startupPolicy.minimumReadyCount) {
            startupCandidateModels.forEach { modelId ->
                missingArtifactConfigFields(modelId).forEach { field ->
                    checks.add("MODEL_ARTIFACT_CONFIG_MISSING:model=$modelId;field=$field")
                }
            }
            return checks
        }
        val manifestIssues = artifactVerifier.manager().validateManifest()
        if (manifestIssues.isNotEmpty()) {
            checks.add(
                "Artifact manifest invalid: ${manifestIssues.joinToString("; ") { "${it.modelId}@${it.version}:${it.code}" }}. " +
                    "Configure SHA-256 metadata for startup models (${startupCandidateModels.joinToString(", ")}).",
            )
            return checks
        }

        val verifiedModels = linkedSetOf<String>()
        configuredModels.forEach { modelId ->
            if (!artifactVerifier.manager().setActiveModel(modelId)) {
                checks.add("Artifact manifest missing required model registration: $modelId.")
                return@forEach
            }
            interactionPlanner.ensureTemplateAvailable(modelId)?.let { templateError ->
                checks.add("Optional runtime model unavailable: $modelId. $templateError")
                return@forEach
            }
            val verification = artifactVerifier.verifyArtifactForModel(modelId)
            if (!verification.passed) {
                checks.add(
                    "Optional runtime model unavailable: $modelId. " +
                        "Artifact verification failed for $modelId: " +
                        artifactVerifier.artifactVerificationFailureMessage(verification),
                )
            } else {
                verifiedModels.add(modelId)
            }
        }
        if (checks.any { !it.startsWith("Optional runtime model unavailable:") }) {
            return checks
        }

        val runtimeBackend = runtimeBackendProvider()
        if (runtimeConfig.requireNativeRuntimeForStartupChecks &&
            runtimeBackend != null &&
            runtimeBackend != RuntimeBackend.NATIVE_JNI.name &&
            runtimeBackend != RuntimeBackend.REMOTE_ANDROID_SERVICE.name
        ) {
            checks.add(
                "Runtime backend is $runtimeBackend. " +
                    "Native JNI runtime is required for closure-path startup checks. " +
                    "Set ${RuntimeConfig.REQUIRE_NATIVE_RUNTIME_STARTUP_ENV}=0 only for local scaffolding lanes.",
            )
            return checks
        }

        val available = inferenceModule.listAvailableModels().toSet()
        val startupEligibleModels = verifiedModels.intersect(available)
        val missing = configuredModels.filterNot { startupEligibleModels.contains(it) }
        val requiredMissing = missing.filter { modelId -> requiredModelIds.contains(modelId) }
        if (requiredMissing.isNotEmpty()) {
            checks.add("Missing runtime model(s): ${requiredMissing.joinToString(", ")}.")
        } else if (startupEligibleModels.size < startupPolicy.minimumReadyCount) {
            checks.add("Missing runtime model(s): ${missing.joinToString(", ")}.")
        } else {
            val optionalMissing = missing.filterNot { modelId -> requiredModelIds.contains(modelId) }
            if (optionalMissing.isNotEmpty() && checks.none { it.contains("Optional runtime model unavailable:") }) {
                checks.add("Optional runtime model unavailable: ${optionalMissing.joinToString(", ")}.")
            }
        }
        if (!policyModule.enforceDataBoundary("inference.startup_check")) {
            checks.add("Policy module rejected startup event type.")
        }
        checks.addAll(networkPolicyClient.startupChecks())
        val networkProbe = networkPolicyClient.enforce("runtime.offline_probe")
        if (networkProbe.allowed) {
            checks.add("Network policy wiring invalid: offline-only mode unexpectedly allowed runtime.offline_probe.")
        }
        checks.addAll(optionalConfigWarnings)
        return checks
    }

    private fun fullyConfiguredRuntimeModelIds(): List<String> {
        val candidateModelIds = linkedSetOf<String>()
        candidateModelIds += modelRegistry.allMetadata().map { metadata -> metadata.modelId }
        candidateModelIds += runtimeConfig.artifactPayloadByModelId.keys
        candidateModelIds += runtimeConfig.artifactFilePathByModelId.keys
        candidateModelIds += runtimeConfig.artifactSha256ByModelId.keys
        candidateModelIds += runtimeConfig.artifactProvenanceIssuerByModelId.keys
        candidateModelIds += runtimeConfig.artifactProvenanceSignatureByModelId.keys
        return candidateModelIds
            .filter { modelId -> missingArtifactConfigFields(modelId).isEmpty() }
    }

    private fun missingArtifactConfigFields(modelId: String): List<String> {
        val missing = mutableListOf<String>()
        val hasPayload = runtimeConfig.artifactPayloadByModelId[modelId]?.isNotEmpty() == true
        val hasPath = runtimeConfig.artifactFilePathByModelId[modelId]?.trim()?.isNotEmpty() == true
        if (!hasPayload && !hasPath) {
            missing.add("payload_or_path")
        }
        if (runtimeConfig.artifactSha256ByModelId[modelId]?.trim().isNullOrEmpty()) {
            missing.add("sha256")
        }
        if (runtimeConfig.artifactProvenanceIssuerByModelId[modelId]?.trim().isNullOrEmpty()) {
            missing.add("provenance_issuer")
        }
        if (runtimeConfig.artifactProvenanceSignatureByModelId[modelId]?.trim().isNullOrEmpty()) {
            missing.add("provenance_signature")
        }
        return missing
    }
}
