package com.pocketagent.runtime

class RuntimeModelLoadPlanningException(
    message: String,
    val errorCode: String = "memory_budget_exceeded",
    val estimatedMemoryMb: Double? = null,
) : RuntimeException(message)
