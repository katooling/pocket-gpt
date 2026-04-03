package com.pocketagent.android

import com.pocketagent.android.runtime.AndroidRuntimeTuningStore
import com.pocketagent.android.runtime.ModelRuntimeLaunchPlanner
import com.pocketagent.android.runtime.AndroidRuntimeProvisioningStore
import com.pocketagent.android.runtime.MvpRuntimeGateway
import com.pocketagent.android.runtime.ModelAdmissionPolicy
import com.pocketagent.android.runtime.ModelEligibilitySignalsProvider
import com.pocketagent.android.runtime.modelspec.NormalizedModelCatalogRegistry
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifestProvider
import com.pocketagent.android.runtime.modelmanager.ModelDownloadManager
import com.pocketagent.core.ConversationModule
import com.pocketagent.memory.MemoryModule

internal data class AppRuntimeGraph(
    val provisioningStore: AndroidRuntimeProvisioningStore,
    val modelDownloadManager: ModelDownloadManager,
    val modelManifestProvider: ModelDistributionManifestProvider,
    val runtimeTuning: AndroidRuntimeTuningStore,
    val conversationModule: ConversationModule,
    val memoryModule: MemoryModule,
    val runtimeFacade: HotSwappableRuntimeFacade,
    val runtimeGateway: MvpRuntimeGateway,
    val eligibilitySignalsProvider: ModelEligibilitySignalsProvider,
    val normalizedModelCatalogRegistry: NormalizedModelCatalogRegistry,
    val runtimeLaunchPlanner: ModelRuntimeLaunchPlanner,
    val modelAdmissionPolicy: ModelAdmissionPolicy,
)
