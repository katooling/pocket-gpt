package com.pocketagent.android

import com.pocketagent.android.runtime.AndroidRuntimeTuningStore
import com.pocketagent.android.runtime.AndroidRuntimeProvisioningStore
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
)
