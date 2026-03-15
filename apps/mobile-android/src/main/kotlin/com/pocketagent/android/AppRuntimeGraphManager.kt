package com.pocketagent.android

import android.content.Context
import com.pocketagent.android.runtime.AndroidRuntimeProvisioningStore
import com.pocketagent.android.runtime.AndroidRuntimeTuningStore
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifestProvider
import com.pocketagent.android.runtime.modelmanager.ModelDownloadManager
import com.pocketagent.core.ConversationModule
import com.pocketagent.core.InMemoryConversationModule
import com.pocketagent.memory.FileBackedMemoryModule
import com.pocketagent.memory.MemoryModule
import com.pocketagent.runtime.RuntimeCompositionRoot

internal class AppRuntimeGraphManager {
    private val lock = Any()
    private var runtimeProvisioningStore: AndroidRuntimeProvisioningStore? = null
    private var runtimeTuningStore: AndroidRuntimeTuningStore? = null
    private var modelDownloadManager: ModelDownloadManager? = null
    private var modelManifestProvider: ModelDistributionManifestProvider? = null
    private var sharedConversationModule: ConversationModule? = null
    private var sharedMemoryModule: MemoryModule? = null
    private var runtimeGraph: AppRuntimeGraph? = null
    private val hotSwappableRuntimeFacade = HotSwappableRuntimeFacade(
        RuntimeCompositionRoot.createFacade(
            conversationModule = getOrCreateConversationModule(),
            memoryModule = getOrCreateMemoryModule(),
        ),
    )

    fun runtimeFacade(): HotSwappableRuntimeFacade = hotSwappableRuntimeFacade

    fun resetForTests() {
        synchronized(lock) {
            runtimeGraph = null
            runtimeTuningStore = null
        }
    }

    fun runtimeTuning(context: Context): AndroidRuntimeTuningStore {
        return synchronized(lock) {
            runtimeTuningStore
                ?: AndroidRuntimeTuningStore(context.applicationContext).also { runtimeTuningStore = it }
        }
    }

    fun currentGraphOrNull(): AppRuntimeGraph? {
        return synchronized(lock) { runtimeGraph }
    }

    fun getOrCreateRuntimeGraph(context: Context): AppRuntimeGraph {
        return synchronized(lock) {
            runtimeGraph ?: createRuntimeGraph(context.applicationContext).also { created ->
                runtimeGraph = created
            }
        }
    }

    private fun createRuntimeGraph(context: Context): AppRuntimeGraph {
        val provisioningStore = runtimeProvisioningStore
            ?: AndroidRuntimeProvisioningStore(context.applicationContext).also { runtimeProvisioningStore = it }
        val runtimeTuning = runtimeTuning(context)
        val conversationModule = getOrCreateConversationModule()
        val memoryModule = getOrCreateMemoryModule()
        val downloadManager = modelDownloadManager ?: ModelDownloadManager(
            context = context.applicationContext,
            provisioningStore = provisioningStore,
        ).also { manager ->
            modelDownloadManager = manager
            manager.syncFromSchedulerState()
        }
        val manifestProvider = modelManifestProvider
            ?: ModelDistributionManifestProvider(context.applicationContext)
                .also { modelManifestProvider = it }
        return AppRuntimeGraph(
            provisioningStore = provisioningStore,
            modelDownloadManager = downloadManager,
            modelManifestProvider = manifestProvider,
            runtimeTuning = runtimeTuning,
            conversationModule = conversationModule,
            memoryModule = memoryModule,
            runtimeFacade = hotSwappableRuntimeFacade,
        )
    }

    private fun getOrCreateConversationModule(): ConversationModule {
        return synchronized(lock) {
            sharedConversationModule ?: InMemoryConversationModule().also { sharedConversationModule = it }
        }
    }

    private fun getOrCreateMemoryModule(): MemoryModule {
        return synchronized(lock) {
            sharedMemoryModule ?: FileBackedMemoryModule.defaultRuntimeModule().also { sharedMemoryModule = it }
        }
    }
}
