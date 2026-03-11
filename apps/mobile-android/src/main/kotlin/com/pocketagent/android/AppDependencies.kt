package com.pocketagent.android

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.util.Log
import com.pocketagent.android.runtime.AndroidRuntimeProvisioningStore
import com.pocketagent.android.runtime.ModelMemoryEstimator
import com.pocketagent.android.runtime.createDefaultAndroidInferenceModule
import com.pocketagent.android.runtime.RuntimeModelImportResult
import com.pocketagent.android.runtime.RuntimeModelLifecycleSnapshot
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
import com.pocketagent.runtime.RuntimeLoadedModel
import com.pocketagent.runtime.RuntimeModelLifecycleCommandResult
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.pocketagent.nativebridge.ModelLifecycleErrorCode
import com.pocketagent.nativebridge.ModelLifecycleState
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
    private val lifecycleCommandMutex = Mutex()
    private val lifecycleState = MutableStateFlow(RuntimeModelLifecycleSnapshot.initial())
    @Volatile
    private var lifecycleActionToken: Long = 0L
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
    var lastMemoryEstimate: ModelMemoryEstimator.EstimationResult? = null
        internal set

    @Volatile
    var runtimeFacadeFactory: () -> MvpRuntimeFacade = productionRuntimeFacadeFactory

    fun resetRuntimeFacadeFactoryForTests() {
        runtimeFacadeFactory = productionRuntimeFacadeFactory
        warmupOrchestrator.cancelActiveWarmup()
        lifecycleState.value = RuntimeModelLifecycleSnapshot.initial()
        lifecycleActionToken = 0L
        lastMemoryEstimate = null
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
            reconcileLifecycleState(graph)
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
        val graph = getOrCreateRuntimeGraph(context)
        val changed = graph.provisioningStore.setActiveVersion(modelId, version)
        if (changed) {
            installProductionRuntime(context)
        }
        reconcileLifecycleState(graph)
        return changed
    }

    fun removeVersion(
        context: Context,
        modelId: String,
        version: String,
    ): Boolean {
        val graph = getOrCreateRuntimeGraph(context)
        val loaded = graph.runtimeFacade.loadedModel()
        if (loaded != null && loaded.modelId == modelId && (loaded.modelVersion == null || loaded.modelVersion == version)) {
            return false
        }
        val removed = graph.provisioningStore.removeVersion(modelId, version)
        if (removed) {
            installProductionRuntime(context)
            graph.modelDownloadManager.refresh()
        }
        reconcileLifecycleState(graph)
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

    fun syncDownloadsFromWorkManager(context: Context) {
        getOrCreateRuntimeGraph(context).modelDownloadManager.syncFromWorkManagerState()
    }

    fun observeDownloads(context: Context): StateFlow<List<DownloadTaskState>> {
        return getOrCreateRuntimeGraph(context).modelDownloadManager.observeDownloads()
    }

    fun observeModelLifecycle(context: Context): StateFlow<RuntimeModelLifecycleSnapshot> {
        reconcileLifecycleState(getOrCreateRuntimeGraph(context))
        return lifecycleState.asStateFlow()
    }

    fun currentModelLifecycle(context: Context): RuntimeModelLifecycleSnapshot {
        reconcileLifecycleState(getOrCreateRuntimeGraph(context))
        return lifecycleState.value
    }

    suspend fun loadInstalledModel(
        context: Context,
        modelId: String,
        version: String,
    ): RuntimeModelLifecycleCommandResult {
        val token = nextLifecycleActionToken()
        val requestedModel = RuntimeLoadedModel(modelId = modelId, modelVersion = version)
        lifecycleState.value = lifecycleState.value.copy(
            state = ModelLifecycleState.LOADING,
            requestedModel = requestedModel,
            errorCode = null,
            errorDetail = null,
            loadingDetail = "Checking model availability...",
            queuedOffload = false,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        return lifecycleCommandMutex.withLock {
            if (token != lifecycleActionToken) {
                val cancelled = cancelledByNewerRequestResult(detail = "load:$modelId@$version")
                applyLifecycleCommandResult(cancelled, requestedModel = requestedModel)
                return@withLock cancelled
            }
            val graph = getOrCreateRuntimeGraph(context)
            val installed = graph.provisioningStore
                .listInstalledVersions(modelId)
                .firstOrNull { descriptor -> descriptor.version == version }
            if (installed == null) {
                val missing = RuntimeModelLifecycleCommandResult.rejected(
                    code = ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE,
                    detail = "installed_version_not_found:$modelId@$version",
                )
                applyLifecycleCommandResult(missing, requestedModel = requestedModel)
                return@withLock missing
            }
            val file = java.io.File(installed.absolutePath)
            if (!file.exists() || !file.isFile) {
                val missing = RuntimeModelLifecycleCommandResult.rejected(
                    code = ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE,
                    detail = "installed_file_missing:${installed.absolutePath}",
                )
                applyLifecycleCommandResult(missing, requestedModel = requestedModel)
                return@withLock missing
            }
            lifecycleState.value = lifecycleState.value.copy(
                loadingDetail = "Checking available memory...",
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            val memoryEstimate = runCatching {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                activityManager?.getMemoryInfo(memInfo)
                val availableBytes = memInfo.availMem.takeIf { it > 0L }
                ModelMemoryEstimator.estimate(
                    modelFilePath = installed.absolutePath,
                    availableMemoryBytes = availableBytes,
                )
            }.getOrNull()
            lastMemoryEstimate = memoryEstimate
            if (memoryEstimate?.fitsInMemory == false) {
                Log.w("AppRuntimeDeps",
                    "MEMORY_WARNING|model=$modelId|estimated_mb=%.0f|available_mb=%.0f".format(
                        memoryEstimate.estimatedMb,
                        memoryEstimate.availableMemoryMb ?: 0.0,
                    ),
                )
            }

            val activated = graph.provisioningStore.setActiveVersion(modelId = modelId, version = version)
            if (!activated) {
                val failed = RuntimeModelLifecycleCommandResult.rejected(
                    code = ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE,
                    detail = "activation_failed:$modelId@$version",
                )
                applyLifecycleCommandResult(failed, requestedModel = requestedModel)
                return@withLock failed
            }
            val previousModelLoaded = lifecycleState.value.loadedModel != null
            if (previousModelLoaded) {
                lifecycleState.value = lifecycleState.value.copy(
                    loadingDetail = "Releasing previous model...",
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            }
            lifecycleState.value = lifecycleState.value.copy(
                loadingDetail = "Initializing runtime...",
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            installProductionRuntime(context)
            lifecycleState.value = lifecycleState.value.copy(
                loadingDetail = "Loading model...",
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            val result = graph.runtimeFacade.loadModel(modelId = modelId, modelVersion = version)
            if (token != lifecycleActionToken) {
                if (result.success) {
                    graph.runtimeFacade.offloadModel(reason = "cancelled_by_newer_request")
                }
                val cancelled = cancelledByNewerRequestResult(detail = "load:$modelId@$version")
                applyLifecycleCommandResult(cancelled, requestedModel = requestedModel)
                return@withLock cancelled
            }
            if (result.success) {
                graph.provisioningStore.recordLastLoadedModel(modelId = modelId, version = version)
            }
            applyLifecycleCommandResult(result, requestedModel = requestedModel)
            result
        }
    }

    suspend fun loadLastUsedModel(context: Context): RuntimeModelLifecycleCommandResult {
        val graph = getOrCreateRuntimeGraph(context)
        val lastUsed = graph.provisioningStore.lastLoadedModel()
            ?: return RuntimeModelLifecycleCommandResult.rejected(
                code = ModelLifecycleErrorCode.MODEL_FILE_UNAVAILABLE,
                detail = "last_loaded_model_missing",
            )
        return loadInstalledModel(
            context = context,
            modelId = lastUsed.modelId,
            version = lastUsed.version,
        )
    }

    suspend fun offloadModel(context: Context, reason: String): RuntimeModelLifecycleCommandResult {
        val token = nextLifecycleActionToken()
        val requestedModel = lifecycleState.value.loadedModel
        lifecycleState.value = lifecycleState.value.copy(
            state = ModelLifecycleState.OFFLOADING,
            requestedModel = requestedModel,
            errorCode = null,
            errorDetail = null,
            queuedOffload = false,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        return lifecycleCommandMutex.withLock {
            if (token != lifecycleActionToken) {
                val cancelled = cancelledByNewerRequestResult(detail = "offload:$reason")
                applyLifecycleCommandResult(cancelled, requestedModel = requestedModel)
                return@withLock cancelled
            }
            val graph = getOrCreateRuntimeGraph(context)
            val result = graph.runtimeFacade.offloadModel(reason = reason)
            applyLifecycleCommandResult(result, requestedModel = requestedModel)
            result
        }
    }

    private fun nextLifecycleActionToken(): Long {
        synchronized(lock) {
            lifecycleActionToken += 1L
            return lifecycleActionToken
        }
    }

    private fun cancelledByNewerRequestResult(detail: String): RuntimeModelLifecycleCommandResult {
        return RuntimeModelLifecycleCommandResult.rejected(
            code = ModelLifecycleErrorCode.CANCELLED_BY_NEWER_REQUEST,
            detail = detail,
            loadedModel = lifecycleState.value.loadedModel,
        )
    }

    private fun applyLifecycleCommandResult(
        result: RuntimeModelLifecycleCommandResult,
        requestedModel: RuntimeLoadedModel?,
    ) {
        val graph = runtimeGraph
        val resolvedLastUsed = graph?.provisioningStore?.lastLoadedModel()?.let { ref ->
            RuntimeLoadedModel(modelId = ref.modelId, modelVersion = ref.version)
        } ?: lifecycleState.value.lastUsedModel
        val updated = when {
            result.success && result.queued -> lifecycleState.value.copy(
                state = ModelLifecycleState.OFFLOADING,
                loadedModel = result.loadedModel ?: lifecycleState.value.loadedModel,
                requestedModel = requestedModel,
                queuedOffload = true,
                errorCode = null,
                errorDetail = result.detail,
                lastUsedModel = resolvedLastUsed,
                updatedAtEpochMs = System.currentTimeMillis(),
            )

            result.success -> lifecycleState.value.copy(
                state = if (result.loadedModel == null) ModelLifecycleState.UNLOADED else ModelLifecycleState.LOADED,
                loadedModel = result.loadedModel,
                requestedModel = null,
                queuedOffload = false,
                errorCode = null,
                errorDetail = result.detail,
                lastUsedModel = resolvedLastUsed,
                updatedAtEpochMs = System.currentTimeMillis(),
            )

            else -> lifecycleState.value.copy(
                state = ModelLifecycleState.FAILED,
                requestedModel = requestedModel,
                queuedOffload = false,
                errorCode = result.errorCode ?: ModelLifecycleErrorCode.UNKNOWN,
                errorDetail = result.detail,
                lastUsedModel = resolvedLastUsed,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
        lifecycleState.value = updated
    }

    private fun reconcileLifecycleState(graph: AppRuntimeGraph) {
        val loaded = graph.runtimeFacade.loadedModel()
        val activeGenerationCount = graph.runtimeFacade.activeGenerationCount()
        val normalizedLoaded = if (loaded != null) {
            val installedVersions = runCatching {
                graph.provisioningStore.listInstalledVersions(loaded.modelId)
            }.getOrElse {
                emptyList()
            }
            val resolvedVersion = loaded.modelVersion
                ?: installedVersions
                    .firstOrNull { descriptor -> descriptor.isActive }
                    ?.version
            if (resolvedVersion == null) {
                graph.runtimeFacade.offloadModel(reason = "reconcile_missing_version")
                null
            } else {
                val installed = installedVersions.firstOrNull { descriptor -> descriptor.version == resolvedVersion }
                val fileExists = installed?.absolutePath
                    ?.let { path -> java.io.File(path).let { it.exists() && it.isFile } }
                    ?: false
                if (!fileExists) {
                    graph.runtimeFacade.offloadModel(reason = "reconcile_missing_file")
                    null
                } else {
                    RuntimeLoadedModel(
                        modelId = loaded.modelId,
                        modelVersion = resolvedVersion,
                    )
                }
            }
        } else {
            null
        }
        val lastUsed = graph.provisioningStore.lastLoadedModel()?.let { ref ->
            RuntimeLoadedModel(modelId = ref.modelId, modelVersion = ref.version)
        }
        lifecycleState.value = lifecycleState.value.copy(
            state = when {
                normalizedLoaded != null -> ModelLifecycleState.LOADED
                lifecycleState.value.queuedOffload && activeGenerationCount > 0 -> ModelLifecycleState.OFFLOADING
                else -> ModelLifecycleState.UNLOADED
            },
            loadedModel = normalizedLoaded,
            requestedModel = null,
            lastUsedModel = lastUsed,
            queuedOffload = lifecycleState.value.queuedOffload && activeGenerationCount > 0,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    }

    private fun getOrCreateRuntimeGraph(context: Context): AppRuntimeGraph {
        val graph = synchronized(lock) {
            runtimeGraph ?: createRuntimeGraph(context.applicationContext).also { created ->
                runtimeGraph = created
            }
        }
        reconcileLifecycleState(graph)
        return graph
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

    override fun loadModel(modelId: String, modelVersion: String?): RuntimeModelLifecycleCommandResult {
        return withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.loadModel(modelId = modelId, modelVersion = modelVersion)
                ?: RuntimeModelLifecycleCommandResult.rejected(
                    code = com.pocketagent.nativebridge.ModelLifecycleErrorCode.UNKNOWN,
                    detail = "runtime_model_load_unsupported",
                )
        }
    }

    override fun offloadModel(reason: String): RuntimeModelLifecycleCommandResult {
        return withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.offloadModel(reason = reason)
                ?: RuntimeModelLifecycleCommandResult.applied()
        }
    }

    override fun loadedModel(): RuntimeLoadedModel? {
        return withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.loadedModel()
        }
    }

    override fun activeGenerationCount(): Int {
        return withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.activeGenerationCount() ?: 0
        }
    }

    override fun touchKeepAlive(): Boolean {
        return withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.touchKeepAlive() ?: false
        }
    }

    override fun shortenKeepAlive(ttlMs: Long): Boolean {
        return withDelegate { facade ->
            (facade as? RuntimeResourceControl)?.shortenKeepAlive(ttlMs) ?: false
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
