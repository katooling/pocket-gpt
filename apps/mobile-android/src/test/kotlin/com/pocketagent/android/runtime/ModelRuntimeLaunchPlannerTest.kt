package com.pocketagent.android.runtime

import com.pocketagent.android.runtime.modelmanager.InstalledArtifactDescriptor
import com.pocketagent.android.runtime.modelmanager.ModelVersionDescriptor
import com.pocketagent.android.runtime.modelspec.DefaultNormalizedModelCatalogRegistry
import com.pocketagent.android.runtime.modelspec.HuggingFaceFileRecord
import com.pocketagent.android.runtime.modelspec.HuggingFaceModelRecord
import com.pocketagent.android.runtime.modelspec.HuggingFaceSourceAdapter
import com.pocketagent.core.model.ModelArtifactRole
import com.pocketagent.core.model.ModelSourceKind
import com.pocketagent.inference.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelRuntimeLaunchPlannerTest {
    @Test
    fun `launch planner reports missing required multimodal artifacts`() {
        val registry = DefaultNormalizedModelCatalogRegistry()
        val planner = DefaultModelRuntimeLaunchPlanner(registry)
        val descriptor = ModelVersionDescriptor(
            modelId = ModelCatalog.QWEN_3_5_0_8B_Q4,
            version = "q4",
            displayName = "Qwen",
            absolutePath = "/tmp/qwen.gguf",
            sha256 = "a".repeat(64),
            provenanceIssuer = "issuer",
            provenanceSignature = "sig",
            runtimeCompatibility = "android-arm64-v8a",
            fileSizeBytes = 123L,
            importedAtEpochMs = 1L,
            isActive = true,
            sourceKind = ModelSourceKind.BUILT_IN,
            artifacts = listOf(
                InstalledArtifactDescriptor(
                    artifactId = "primary",
                    role = ModelArtifactRole.PRIMARY_GGUF,
                    fileName = "qwen.gguf",
                    absolutePath = "/tmp/qwen.gguf",
                ),
            ),
        )

        val plan = planner.planInstalledModel(descriptor)

        assertTrue(plan.loadBlocked)
        assertTrue(plan.missingRequiredArtifacts.any { artifact -> artifact.contains("mmproj") })
    }

    @Test
    fun `normalized registry merges local imported variants into built in spec`() {
        val registry = DefaultNormalizedModelCatalogRegistry(
            installedVersionsProvider = { modelId ->
                if (modelId == ModelCatalog.QWEN_3_5_0_8B_Q4) {
                    listOf(
                        ModelVersionDescriptor(
                            modelId = modelId,
                            version = "manual-q6",
                            displayName = "Qwen import",
                            absolutePath = "/tmp/qwen-import.gguf",
                            sha256 = "b".repeat(64),
                            provenanceIssuer = "issuer",
                            provenanceSignature = "sig",
                            runtimeCompatibility = "android-arm64-v8a",
                            fileSizeBytes = 321L,
                            importedAtEpochMs = 2L,
                            isActive = false,
                            sourceKind = ModelSourceKind.LOCAL_IMPORT,
                        ),
                    )
                } else {
                    emptyList()
                }
            },
            knownModelIdsProvider = { setOf(ModelCatalog.QWEN_3_5_0_8B_Q4) },
        )

        val spec = registry.specFor(ModelCatalog.QWEN_3_5_0_8B_Q4)

        assertTrue(spec?.variants?.any { variant -> variant.variantId == "manual-q6" } == true)
        assertTrue(spec?.variants?.isNotEmpty() == true)
    }

    @Test
    fun `hugging face source adapter preserves auxiliary artifact roles`() {
        val adapter = HuggingFaceSourceAdapter()

        val spec = adapter.adapt(
            listOf(
                HuggingFaceModelRecord(
                    modelId = "hf-qwen-vlm",
                    displayName = "HF Qwen VLM",
                    repository = "Qwen/Qwen-VLM-GGUF",
                    author = "Qwen",
                    architecture = "qwen2vl",
                    contextLength = 32768,
                    chatTemplateName = "chatml",
                    files = listOf(
                        HuggingFaceFileRecord(
                            fileName = "model.gguf",
                            downloadUrl = "https://example.test/model.gguf",
                        ),
                        HuggingFaceFileRecord(
                            fileName = "mmproj.gguf",
                            downloadUrl = "https://example.test/mmproj.gguf",
                            role = ModelArtifactRole.MMPROJ,
                        ),
                    ),
                ),
            ),
        ).single()

        assertEquals(ModelSourceKind.HUGGING_FACE, spec.source.kind)
        assertTrue(
            spec.variants.single().artifactBundle.artifacts.any { artifact ->
                artifact.role == ModelArtifactRole.MMPROJ
            },
        )
    }
}
