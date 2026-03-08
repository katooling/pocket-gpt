package com.pocketagent.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import com.pocketagent.android.runtime.DefaultProvisioningGateway
import com.pocketagent.android.runtime.AndroidGpuOffloadSupport
import com.pocketagent.android.runtime.MvpRuntimeGateway
import com.pocketagent.android.runtime.RuntimeBootstrapper
import com.pocketagent.android.ui.ChatViewModel
import com.pocketagent.android.ui.ChatViewModelFactory
import com.pocketagent.android.ui.ModelProvisioningViewModel
import com.pocketagent.android.ui.ModelProvisioningViewModelFactory
import com.pocketagent.android.ui.PocketAgentApp
import com.pocketagent.android.ui.PocketAgentTheme
import com.pocketagent.android.ui.controllers.AndroidTelemetryDeviceStateProvider
import com.pocketagent.android.ui.state.AndroidSessionPersistence

class MainActivity : ComponentActivity() {
    private val runtimeGateway by lazy {
        MvpRuntimeGateway(
            facade = RuntimeBootstrapper.runtimeFacade(),
            deviceGpuOffloadSupport = AndroidGpuOffloadSupport(applicationContext),
        )
    }

    private val viewModel: ChatViewModel by viewModels {
        ChatViewModelFactory(
            runtimeFacade = runtimeGateway,
            sessionPersistence = AndroidSessionPersistence(applicationContext),
            deviceStateProvider = AndroidTelemetryDeviceStateProvider(applicationContext),
        )
    }

    private val provisioningViewModel: ModelProvisioningViewModel by viewModels {
        ModelProvisioningViewModelFactory(
            gateway = DefaultProvisioningGateway(applicationContext),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RuntimeBootstrapper.installProductionRuntime(applicationContext)
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
    }
}
