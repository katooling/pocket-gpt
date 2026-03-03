package com.pocketagent.inference

object ModelCatalog {
    const val SMOKE_ECHO_120M = "smoke-echo-120m-q4"
    const val QWEN_3_5_0_8B_Q4 = "qwen3.5-0.8b-q4"
    const val QWEN_3_5_2B_Q4 = "qwen3.5-2b-q4"

    fun baselineModels(): List<String> = listOf(
        SMOKE_ECHO_120M,
        QWEN_3_5_0_8B_Q4,
        QWEN_3_5_2B_Q4,
    )
}
