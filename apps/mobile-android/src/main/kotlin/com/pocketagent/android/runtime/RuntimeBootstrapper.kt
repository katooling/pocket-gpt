package com.pocketagent.android.runtime

import android.content.Context
import com.pocketagent.android.AppRuntimeDependencies
import com.pocketagent.runtime.MvpRuntimeFacade

object RuntimeBootstrapper {
    fun installProductionRuntime(context: Context) {
        AppRuntimeDependencies.installProductionRuntime(context.applicationContext)
    }

    fun runtimeFacade(): MvpRuntimeFacade {
        return AppRuntimeDependencies.runtimeFacadeFactory()
    }
}
