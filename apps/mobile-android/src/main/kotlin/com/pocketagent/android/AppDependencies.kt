package com.pocketagent.android

import android.content.Context
import android.net.Uri
import android.util.Log
import com.pocketagent.android.runtime.AndroidRuntimeProvisioningStore
import com.pocketagent.android.runtime.RuntimeModelImportResult
import com.pocketagent.android.runtime.RuntimeProvisioningSnapshot
import com.pocketagent.android.runtime.modelmanager.DownloadTaskState
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifest
import com.pocketagent.android.runtime.modelmanager.ModelDistributionManifestProvider
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import com.pocketagent.android.runtime.modelmanager.ModelDownloadManager
import com.pocketagent.android.runtime.modelmanager.StorageSummary
import com.pocketagent.core.ConversationModule
import com.pocketagent.core.InMemoryConversationModule
import com.pocketagent.core.RoutingMode
import com.pocketagent.core.SessionId
import com.pocketagent.core.Turn
import com.pocketagent.runtime.ChatStreamEvent
import com.pocketagent.runtime.ImageAnalysisResult
import com.pocketagent.runtime.MvpRuntimeFacade
import com.pocketagent.runtime.RuntimeCompositionRoot
import com.pocketagent.runtime.StreamChatRequestV2
import com.pocketagent.runtime.StreamUserMessageRequest
import com.pocketagent.runtime.ToolExecutionResult
import com.pocketagent.memory.FileBackedMemoryModule
import com.pocketagent.memory.MemoryModule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

object AppRuntimeDependencies {
    private val lock = Any()
    private var runtimeProvisioningStore: AndroidRuntimeProvisioningStore? = null
    private var modelDownloadManager: ModelDownloadManager? = null
    private var modelManifestProvider: ModelDistributionManifestProvider? = null
    private var sharedConversationModule: ConversationModule? = null
    private var sharedMemoryModule: MemoryModule? = null
    private val hotSwappableRuntimeFacade = HotSwappableRuntimeFacade(
        RuntimeCompositionRoot.createFacade(
            conversationModule = getOrCreateConversationModule(),
            memoryModule = getOrCreateMemoryModule(),
        ),
    )
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
                RuntimeCompositionRoot.createFacade(
                    runtimeConfig = store.runtimeConfig(),
                    conversationModule = getOrCreateConversationModule(),
                    memoryModule = getOrCreateMemoryModule(),
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

    fun cancelDownload(context: Context, taskId: String) {
        getOrCreateDownloadManager(context).cancelDownload(taskId)
    }

    fun observeDownloads(context: Context): StateFlow<List<DownloadTaskState>> {
        return getOrCreateDownloadManager(context).observeDownloads()
    }

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

internal class HotSwappableRuntimeFacade(
    initial: MvpRuntimeFacade,
) : MvpRuntimeFacade {
    private val lock = ReentrantReadWriteLock()
    private var delegate: MvpRuntimeFacade = initial
    private var replacementCounter: Long = 0L

    fun replace(newDelegate: MvpRuntimeFacade) {
        lock.write {
            delegate = newDelegate
            replacementCounter += 1L
            safeLogInfo("RUNTIME_SWAP|phase=replaced|counter=$replacementCounter")
        }
    }

    override fun createSession(): SessionId = withDelegate { it.createSession() }

    override fun streamUserMessage(request: StreamUserMessageRequest): Flow<ChatStreamEvent> {
        return withDelegate { it.streamUserMessage(request) }
    }

    override fun streamChat(request: StreamChatRequestV2): Flow<ChatStreamEvent> {
        return withDelegate { it.streamChat(request) }
    }

    override fun cancelGeneration(sessionId: SessionId): Boolean = withDelegate { it.cancelGeneration(sessionId) }

    override fun cancelGenerationByRequest(requestId: String): Boolean = withDelegate { it.cancelGenerationByRequest(requestId) }

    override fun runTool(toolName: String, jsonArgs: String): String = withDelegate { it.runTool(toolName, jsonArgs) }

    override fun runToolDetailed(toolName: String, jsonArgs: String): ToolExecutionResult {
        return withDelegate { it.runToolDetailed(toolName = toolName, jsonArgs = jsonArgs) }
    }

    override fun analyzeImage(imagePath: String, prompt: String): String = withDelegate { it.analyzeImage(imagePath, prompt) }

    override fun analyzeImageDetailed(imagePath: String, prompt: String): ImageAnalysisResult {
        return withDelegate { it.analyzeImageDetailed(imagePath = imagePath, prompt = prompt) }
    }

    override fun exportDiagnostics(): String = withDelegate { it.exportDiagnostics() }

    override fun setRoutingMode(mode: RoutingMode) {
        withDelegate { it.setRoutingMode(mode) }
    }

    override fun getRoutingMode(): RoutingMode = withDelegate { it.getRoutingMode() }

    override fun runStartupChecks(): List<String> = withDelegate { it.runStartupChecks() }

    override fun restoreSession(sessionId: SessionId, turns: List<Turn>) {
        withDelegate { it.restoreSession(sessionId, turns) }
    }

    override fun deleteSession(sessionId: SessionId): Boolean = withDelegate { it.deleteSession(sessionId) }

    override fun runtimeBackend(): String? = withDelegate { it.runtimeBackend() }

    override fun supportsGpuOffload(): Boolean = withDelegate { it.supportsGpuOffload() }

    private inline fun <T> withDelegate(block: (MvpRuntimeFacade) -> T): T {
        return lock.read {
            block(delegate)
        }
    }

    private fun safeLogInfo(message: String) {
        runCatching { Log.i("AppRuntimeDeps", message) }
    }
}
