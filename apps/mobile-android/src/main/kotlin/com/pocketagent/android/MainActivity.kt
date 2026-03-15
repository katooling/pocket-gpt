package com.pocketagent.android

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.material3.Surface
import com.pocketagent.android.runtime.AndroidGpuOffloadQualifier
import com.pocketagent.android.runtime.AndroidGpuOffloadSupport
import com.pocketagent.android.runtime.DefaultProvisioningGateway
import com.pocketagent.android.runtime.MvpRuntimeGateway
import com.pocketagent.android.runtime.RuntimeBootstrapper
import com.pocketagent.android.ui.ChatViewModel
import com.pocketagent.android.ui.ChatViewModelFactory
import com.pocketagent.android.ui.ModelProvisioningViewModel
import com.pocketagent.android.ui.ModelProvisioningViewModelFactory
import com.pocketagent.android.ui.PocketAgentApp
import com.pocketagent.android.ui.PocketAgentTheme
import com.pocketagent.android.ui.controllers.AndroidTelemetryDeviceStateProvider
import com.pocketagent.android.data.chat.AndroidSessionPersistence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val runtimeTuning by lazy {
        RuntimeBootstrapper.runtimeTuning(applicationContext)
    }

    private val runtimeGateway by lazy {
        MvpRuntimeGateway(
            facade = RuntimeBootstrapper.runtimeFacade(),
            deviceGpuOffloadSupport = AndroidGpuOffloadSupport(applicationContext),
            gpuOffloadQualifier = AndroidGpuOffloadQualifier(applicationContext),
            runtimeTuning = runtimeTuning,
        )
    }

    private val viewModel: ChatViewModel by viewModels {
        ChatViewModelFactory(
            runtimeFacade = runtimeGateway,
            sessionPersistence = AndroidSessionPersistence(applicationContext),
            deviceStateProvider = AndroidTelemetryDeviceStateProvider(applicationContext),
            runtimeTuning = runtimeTuning,
        )
    }

    private val provisioningViewModel: ModelProvisioningViewModel by viewModels {
        ModelProvisioningViewModelFactory(
            gateway = DefaultProvisioningGateway(applicationContext),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PocketAgentTheme {
                Surface {
                    PocketAgentApp(
                        viewModel = viewModel,
                        provisioningViewModel = provisioningViewModel,
                    )
                }
            }
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                RuntimeBootstrapper.installProductionRuntime(applicationContext)
            }
            viewModel.refreshRuntimeReadiness()
        }
    }

    override fun onResume() {
        super.onResume()
        runtimeGateway.onAppForeground()
        runtimeGateway.touchKeepAlive()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val evicted = when {
            level >= TRIM_MEMORY_COMPLETE_LEVEL -> runtimeGateway.evictResidentModel("trim_complete")
            level >= TRIM_MEMORY_BACKGROUND_LEVEL -> runtimeGateway.evictResidentModel("trim_background")
            level >= TRIM_MEMORY_RUNNING_CRITICAL_LEVEL -> runtimeGateway.evictResidentModel("trim_critical")
            else -> {
                when {
                    level >= TRIM_MEMORY_RUNNING_LOW_LEVEL -> runtimeGateway.shortenKeepAlive(15_000L)
                    level >= TRIM_MEMORY_RUNNING_MODERATE_LEVEL -> runtimeGateway.shortenKeepAlive(60_000L)
                    level >= TRIM_MEMORY_UI_HIDDEN_LEVEL -> runtimeGateway.shortenKeepAlive(120_000L)
                }
                false
            }
        }
        if (evicted) {
            recordAvailableMemoryBudget()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runtimeGateway.evictResidentModel(reason = "low_memory")
        recordAvailableMemoryBudget()
    }

    override fun onStop() {
        runtimeGateway.onAppBackground()
        recordAvailableMemoryBudget()
        super.onStop()
    }

    private fun recordAvailableMemoryBudget() {
        runCatching {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val availMb = memInfo.availMem.toDouble() / (1024.0 * 1024.0)
            runtimeTuning.memoryBudgetTracker.recordAvailableMemoryAfterRelease(availMb)
        }
    }

    private companion object {
        private const val TRIM_MEMORY_RUNNING_MODERATE_LEVEL = 5
        private const val TRIM_MEMORY_RUNNING_LOW_LEVEL = 10
        private const val TRIM_MEMORY_RUNNING_CRITICAL_LEVEL = 15
        private const val TRIM_MEMORY_UI_HIDDEN_LEVEL = 20
        private const val TRIM_MEMORY_BACKGROUND_LEVEL = 40
        private const val TRIM_MEMORY_COMPLETE_LEVEL = 80
    }
}
