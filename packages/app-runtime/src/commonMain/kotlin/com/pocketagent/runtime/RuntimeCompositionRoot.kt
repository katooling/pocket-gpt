package com.pocketagent.runtime

import com.pocketagent.core.ConversationModule
import com.pocketagent.core.InMemoryConversationModule
import com.pocketagent.memory.FileBackedMemoryModule
import com.pocketagent.memory.MemoryModule

object RuntimeCompositionRoot {
    fun createContainer(
        runtimeConfig: RuntimeConfig = RuntimeConfig.fromEnvironment(),
        conversationModule: ConversationModule = InMemoryConversationModule(),
        memoryModule: MemoryModule = FileBackedMemoryModule.defaultRuntimeModule(),
    ): RuntimeContainer {
        return DefaultRuntimeContainer(
            runtimeConfig = runtimeConfig,
            conversationModule = conversationModule,
            memoryModule = memoryModule,
        )
    }

    fun createFacade(
        runtimeConfig: RuntimeConfig = RuntimeConfig.fromEnvironment(),
        conversationModule: ConversationModule = InMemoryConversationModule(),
        memoryModule: MemoryModule = FileBackedMemoryModule.defaultRuntimeModule(),
    ): MvpRuntimeFacade {
        return DefaultMvpRuntimeFacade(
            container = createContainer(
                runtimeConfig = runtimeConfig,
                conversationModule = conversationModule,
                memoryModule = memoryModule,
            ),
        )
    }
}
