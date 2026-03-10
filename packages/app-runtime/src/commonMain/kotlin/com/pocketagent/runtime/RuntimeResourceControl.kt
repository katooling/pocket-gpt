package com.pocketagent.runtime

interface RuntimeResourceControl {
    fun evictResidentModel(reason: String = "manual"): Boolean
}
