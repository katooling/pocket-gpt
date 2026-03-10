package com.pocketagent.android

import android.content.Context
import android.net.Uri
import android.util.Log
import com.pocketagent.android.runtime.AndroidRuntimeProvisioningStore
import com.pocketagent.android.runtime.createDefaultAndroidInferenceModule
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
import com.pocketagent.runtime.RuntimeResourceControl
import com.pocketagent.runtime.RuntimeWarmupSupport
import com.pocketagent.runtime.StreamChatRequestV2
import com.pocketagent.runtime.ToolExecutionResult
import com.pocketagent.runtime.WarmupResult
import com.pocketagent.memory.FileBackedMemoryModule
import com.pocketagent.memory.MemoryModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private var runtimeGraph: AppRuntimeGraph? = null
    private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val warmupOrchestrator = RuntimeWarmupOrchestrator(
        scope = warmupScope,
        logger = { message -> runCatching { Log.i("AppRuntimeDeps", message) } },
    )
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
        warmupOrchestrator.cancelActiveWarmup()
        synchronized(lock) {
            runtimeGraph = null
        }
    }

    internal fun cancelBackgroundWorkForTests() {
        warmupOrchestrator.cancelActiveWarmup()
    }

    fun installProductionRuntime(context: Context) {
        if (runtimeFacadeFactory !== productionRuntimeFacadeFactory) {
            return
        }
        synchronized(lock) {
            val graph = getOrCreateRuntimeGraph(context)
            val store = graph.provisioningStore
            val newFacade = RuntimeCompositionRoot.createFacade(
                runtimeConfig = store.runtimeConfig(),
                conversationModule = graph.conversationModule,
                memoryModule = graph.memoryModule,
                inferenceModule = createDefaultAndroidInferenceModule(context.applicationContext),
            )
            graph.runtimeFacade.replace(newFacade)
            if (startupWarmupEnabled()) {
                scheduleWarmupIfSupported(newFacade)
            } else {
                Log.i("AppRuntimeDeps", "WARMUP|startup=disabled")
            }
        }
    }

    private fun startupWarmupEnabled(): Boolean {
        val raw = System.getenv("POCKETGPT_WARMUP_ON_STARTUP")
            ?.trim()
            ?.lowercase()
            ?: return false
        return raw == "1" || raw == "true" || raw == "yes"
    }

    private fun scheduleWarmupIfSupported(facade: MvpRuntimeFacade) {
        warmupOrchestrator.scheduleWarmupIfSupported(facade as? RuntimeWarmupSupport)
    }

    fun currentProvisioningSnapshot(context: Context): RuntimeProvisioningSnapshot {
        return getOrCreateRuntimeGraph(context).provisioningStore.snapshot()
    }

    suspend fun importModelFromUri(
        context: Context,
        modelId: String,
        sourceUri: Uri,
    ): RuntimeModelImportResult {
        val graph = getOrCreateRuntimeGraph(context)
        val store = graph.provisioningStore
        val result = store.importModel(modelId = modelId, sourceUri = sourceUri)
        installProductionRuntime(context)
        graph.modelDownloadManager.refresh()
        return result
    }

    suspend fun seedModelFromAbsolutePath(
        context: Context,
        modelId: String,
        absolutePath: String,
    ): RuntimeModelImportResult {
        val graph = getOrCreateRuntimeGraph(context)
        val store = graph.provisioningStore
        val result = store.seedModelFromAbsolutePath(modelId = modelId, absolutePath = absolutePath)
        installProductionRuntime(context)
        graph.modelDownloadManager.refresh()
        return result
    }

    fun listInstalledVersions(
        context: Context,
        modelId: String,
    ) = getOrCreateRuntimeGraph(context).provisioningStore.listInstalledVersions(modelId)

    fun setActiveVersion(
        context: Context,
        modelId: String,
        version: String,
    ): Boolean {
        val changed = getOrCreateRuntimeGraph(context).provisioningStore.setActiveVersion(modelId, version)
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
        val graph = getOrCreateRuntimeGraph(context)
        val removed = graph.provisioningStore.removeVersion(modelId, version)
        if (removed) {
            installProductionRuntime(context)
            graph.modelDownloadManager.refresh()
        }
        return removed
    }

    fun storageSummary(context: Context): StorageSummary {
        return getOrCreateRuntimeGraph(context).provisioningStore.storageSummary()
    }

    suspend fun loadModelDistributionManifest(context: Context): ModelDistributionManifest {
        return getOrCreateRuntimeGraph(context).modelManifestProvider.loadManifest()
    }

    fun enqueueDownload(
        context: Context,
        version: ModelDistributionVersion,
    ): String {
        return getOrCreateRuntimeGraph(context).modelDownloadManager.enqueueDownload(version)
    }

    fun pauseDownload(context: Context, taskId: String) {
        getOrCreateRuntimeGraph(context).modelDownloadManager.pauseDownload(taskId)
    }

    fun resumeDownload(context: Context, taskId: String) {
        getOrCreateRuntimeGraph(context).modelDownloadManager.resumeDownload(taskId)
    }

    fun retryDownload(context: Context, taskId: String) {
        getOrCreateRuntimeGraph(context).modelDownloadManager.retryDownload(taskId)
    }

    fun cancelDownload(context: Context, taskId: String) {
        getOrCreateRuntimeGraph(context).modelDownloadManager.cancelDownload(taskId)
    }

    fun observeDownloads(context: Context): StateFlow<List<DownloadTaskState>> {
        return getOrCreateRuntimeGraph(context).modelDownloadManager.observeDownloads()
    }

    private fun getOrCreateRuntimeGraph(context: Context): AppRuntimeGraph {
        return synchronized(lock) {
            runtimeGraph ?: createRuntimeGraph(context.applicationContext).also { runtimeGraph = it }
        }
    }

    private fun createRuntimeGraph(context: Context): AppRuntimeGraph {
        val provisioningStore = runtimeProvisioningStore
            ?: AndroidRuntimeProvisioningStore(context.applicationContext).also { runtimeProvisioningStore = it }
        val conversationModule = getOrCreateConversationModule()
        val memoryModule = getOrCreateMemoryModule()
        val downloadManager = modelDownloadManager ?: ModelDownloadManager(
            context = context.applicationContext,
            provisioningStore = provisioningStore,
        ).also { manager ->
            modelDownloadManager = manager
            manager.syncFromWorkManagerState()
        }
        val manifestProvider = modelManifestProvider
            ?: ModelDistributionManifestProvider(context.applicationContext)
                .also { modelManifestProvider = it }
        return AppRuntimeGraph(
            provisioningStore = provisioningStore,
            modelDownloadManager = downloadManager,
            modelManifestProvider = manifestProvider,
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

internal class HotSwappableRuntimeFacade(
    initial: MvpRuntimeFacade,
) : MvpRuntimeFacade, RuntimeWarmupSupport, RuntimeResourceControl {
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

    @Suppress("DEPRECATION")
    override fun streamUserMessage(request: com.pocketagent.runtime.StreamUserMessageRequest): Flow<ChatStreamEvent> {
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

    override fun warmupActiveModel(): WarmupResult {
        return withDelegate { facade ->
            (facade as? RuntimeWarmupSupport)?.warmupActiveModel()
                ?: WarmupResult.skipped("warmup_unsupported")
        }
    }

    override fun evictResidentModel(reason: String): Boolean {
        return withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.evictResidentModel(reason) ?: false
        }
    }

    override fun onTrimMemory(level: Int): Boolean {
        return withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.onTrimMemory(level) ?: false
        }
    }

    override fun onAppBackground(): Boolean {
        return withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.onAppBackground() ?: false
        }
    }

    private inline fun <T> withDelegate(block: (MvpRuntimeFacade) -> T): T {
        return lock.read {
            block(delegate)
        }
    }

    private fun safeLogInfo(message: String) {
        runCatching { Log.i("AppRuntimeDeps", message) }
    }
}
