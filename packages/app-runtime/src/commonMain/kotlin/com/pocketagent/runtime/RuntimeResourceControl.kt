package com.pocketagent.runtime

interface RuntimeResourceControl {
    fun evictResidentModel(reason: String = "manual"): Boolean
    fun touchKeepAlive(): Boolean = false
    fun shortenKeepAlive(ttlMs: Long): Boolean = false
    fun onTrimMemory(level: Int): Boolean = false
    fun onAppBackground(): Boolean = false
}
