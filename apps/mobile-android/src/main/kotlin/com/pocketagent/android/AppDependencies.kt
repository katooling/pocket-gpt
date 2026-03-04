package com.pocketagent.android

import com.pocketagent.runtime.DefaultMvpRuntimeFacade
import com.pocketagent.runtime.MvpRuntimeFacade

object AppRuntimeDependencies {
    var runtimeFacadeFactory: () -> MvpRuntimeFacade = { DefaultMvpRuntimeFacade() }
}
