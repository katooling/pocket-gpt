package com.pocketagent.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.pocketagent.android.ui.ChatViewModel
import com.pocketagent.android.ui.ChatViewModelFactory
import com.pocketagent.android.ui.PocketAgentApp
import com.pocketagent.android.ui.runtime.DefaultMvpRuntimeFacade
import com.pocketagent.android.ui.runtime.MvpRuntimeFacade
import com.pocketagent.android.ui.state.AndroidSessionPersistence

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels {
        ChatViewModelFactory(
            runtimeFacade = AppRuntimeDependencies.runtimeFacadeFactory(),
            sessionPersistence = AndroidSessionPersistence(applicationContext),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    PocketAgentApp(viewModel = viewModel)
                }
            }
        }
    }
}

object AppRuntimeDependencies {
    var runtimeFacadeFactory: () -> MvpRuntimeFacade = { DefaultMvpRuntimeFacade() }
}
