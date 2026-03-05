package com.pocketagent.android

import android.content.Context
import android.net.Uri
import com.pocketagent.android.runtime.AndroidRuntimeProvisioningStore
import com.pocketagent.android.runtime.RuntimeModelImportResult
import com.pocketagent.android.runtime.RuntimeProvisioningSnapshot
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifestProvider
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelDownloadManager
import com.pocketagent.android.runtime.modelmanager.StorageSummary
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.DefaultMvpRuntimeFacade
import com.pocketagent.runtime.DefaultRuntimeContainer
import com.pocketagent.runtime.MvpRuntimeFacade
import com.pocketagent.runtime.StreamUserMessageRequest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow

object AppRuntimeDependencies {
    private val lock = Any()
    private var runtimeProvisioningStore: AndroidRuntimeProvisioningStore? = null
    private var modelDownloadManager: ModelDownloadManager? = null
    private var modelManifestProvider: ModelDistributionManifestProvider? = null
    private val hotSwappableRuntimeFacade = HotSwappableRuntimeFacade(DefaultMvpRuntimeFacade())
    private val productionRuntimeFacadeFactory: () -> MvpRuntimeFacade = { hotSwappableRuntimeFacade }

    @Volatile
    var runtimeFacadeFactory: () -> MvpRuntimeFacade = productionRuntimeFacadeFactory

    fun resetRuntimeFacadeFactoryForTests() {
        runtimeFacadeFactory = productionRuntimeFacadeFactory
    }

    fun installProductionRuntime(context: Context) {
        if (runtimeFacadeFactory !== productionRuntimeFacadeFactory) {
            return
        }
        synchronized(lock) {
            val store = runtimeProvisioningStore
                ?: AndroidRuntimeProvisioningStore(context.applicationContext).also { runtimeProvisioningStore = it }
            hotSwappableRuntimeFacade.replace(
                DefaultMvpRuntimeFacade(
                    container = DefaultRuntimeContainer(runtimeConfig = store.runtimeConfig()),
                ),
            )
        }
    }

    fun currentProvisioningSnapshot(context: Context): RuntimeProvisioningSnapshot {
        val store = getOrCreateProvisioningStore(context)
        return store.snapshot()
    }

    suspend fun importModelFromUri(
        context: Context,
        modelId: String,
        sourceUri: Uri,
    ): RuntimeModelImportResult {
        val store = getOrCreateProvisioningStore(context)
        val result = store.importModel(modelId = modelId, sourceUri = sourceUri)
        installProductionRuntime(context)
        getOrCreateDownloadManager(context).refresh()
        return result
    }

    suspend fun seedModelFromAbsolutePath(
        context: Context,
        modelId: String,
        absolutePath: String,
    ): RuntimeModelImportResult {
        val store = getOrCreateProvisioningStore(context)
        val result = store.seedModelFromAbsolutePath(modelId = modelId, absolutePath = absolutePath)
        installProductionRuntime(context)
        getOrCreateDownloadManager(context).refresh()
        return result
    }

    fun listInstalledVersions(
        context: Context,
        modelId: String,
    ) = getOrCreateProvisioningStore(context).listInstalledVersions(modelId)

    fun setActiveVersion(
        context: Context,
        modelId: String,
        version: String,
    ): Boolean {
        val changed = getOrCreateProvisioningStore(context).setActiveVersion(modelId, version)
        if (changed) {
            installProductionRuntime(context)
        }
        return changed
    }

    fun removeVersion(
        context: Context,
        modelId: String,
        version: String,
    ): Boolean {
        val removed = getOrCreateProvisioningStore(context).removeVersion(modelId, version)
        if (removed) {
            installProductionRuntime(context)
            getOrCreateDownloadManager(context).refresh()
        }
        return removed
    }

    fun storageSummary(context: Context): StorageSummary {
        return getOrCreateProvisioningStore(context).storageSummary()
    }

    suspend fun loadModelDistributionManifest(context: Context): ModelDistributionManifest {
        return synchronized(lock) {
            modelManifestProvider
                ?: ModelDistributionManifestProvider(context.applicationContext)
                    .also { modelManifestProvider = it }
        }.loadManifest()
    }

    fun enqueueDownload(
        context: Context,
        version: ModelDistributionVersion,
    ): String {
        return getOrCreateDownloadManager(context).enqueueDownload(version)
    }

    fun pauseDownload(context: Context, taskId: String) {
        getOrCreateDownloadManager(context).pauseDownload(taskId)
    }

    fun resumeDownload(context: Context, taskId: String) {
        getOrCreateDownloadManager(context).resumeDownload(taskId)
    }

    fun retryDownload(context: Context, taskId: String) {
        getOrCreateDownloadManager(context).retryDownload(taskId)
    }

    fun observeDownloads(context: Context): StateFlow<List<DownloadTaskState>> {
        return getOrCreateDownloadManager(context).observeDownloads()
    }

    fun areModelDownloadsEnabled(): Boolean = BuildConfig.MODEL_DOWNLOADS_ENABLED

    private fun getOrCreateProvisioningStore(context: Context): AndroidRuntimeProvisioningStore {
        return synchronized(lock) {
            runtimeProvisioningStore
                ?: AndroidRuntimeProvisioningStore(context.applicationContext).also { runtimeProvisioningStore = it }
        }
    }

    private fun getOrCreateDownloadManager(context: Context): ModelDownloadManager {
        return synchronized(lock) {
            modelDownloadManager ?: ModelDownloadManager(
                context = context.applicationContext,
                provisioningStore = getOrCreateProvisioningStore(context),
            ).also { manager ->
                modelDownloadManager = manager
                manager.syncFromWorkManagerState()
            }
        }
    }
}

private class HotSwappableRuntimeFacade(
    initial: MvpRuntimeFacade,
) : MvpRuntimeFacade {
    @Volatile
    private var delegate: MvpRuntimeFacade = initial

    fun replace(newDelegate: MvpRuntimeFacade) {
        delegate = newDelegate
    }

    override fun createSession(): SessionId = delegate.createSession()

    override fun streamUserMessage(request: StreamUserMessageRequest): Flow<ChatStreamEvent> {
        return delegate.streamUserMessage(request)
    }

    override fun runTool(toolName: String, jsonArgs: String): String = delegate.runTool(toolName, jsonArgs)

    override fun analyzeImage(imagePath: String, prompt: String): String = delegate.analyzeImage(imagePath, prompt)

    override fun exportDiagnostics(): String = delegate.exportDiagnostics()

    override fun setRoutingMode(mode: RoutingMode) {
        delegate.setRoutingMode(mode)
    }

    override fun getRoutingMode(): RoutingMode = delegate.getRoutingMode()

    override fun runStartupChecks(): List<String> = delegate.runStartupChecks()

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        delegate.restoreSession(sessionId, turns)
    }

    override fun deleteSession(sessionId: SessionId): Boolean = delegate.deleteSession(sessionId)

    override fun runtimeBackend(): String? = delegate.runtimeBackend()
}
