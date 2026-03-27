package com.pocketagent.android.ui

import androidx.lifecycle.ViewModel
import com.pocketagent.android.runtime.modelmanager.ModelDistributionVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChatAppViewModel : ViewModel() {

    private val _selectedModelIdForImport = MutableStateFlow<String?>(null)
    val selectedModelIdForImport = _selectedModelIdForImport.asStateFlow()

    fun setSelectedModelIdForImport(modelId: String?) {
        _selectedModelIdForImport.value = modelId
    }

    private val _pendingGetReadyActivation = MutableStateFlow<Pair<String, String>?>(null)
    val pendingGetReadyActivation = _pendingGetReadyActivation.asStateFlow()

    fun setPendingGetReadyActivation(value: Pair<String, String>?) {
        _pendingGetReadyActivation.value = value
    }

    private val _pendingMeteredWarningVersion = MutableStateFlow<ModelDistributionVersion?>(null)
    val pendingMeteredWarningVersion = _pendingMeteredWarningVersion.asStateFlow()

    fun setPendingMeteredWarningVersion(version: ModelDistributionVersion?) {
        _pendingMeteredWarningVersion.value = version
    }

    private val _pendingNotificationPermissionVersion = MutableStateFlow<ModelDistributionVersion?>(null)
    val pendingNotificationPermissionVersion = _pendingNotificationPermissionVersion.asStateFlow()

    fun setPendingNotificationPermissionVersion(version: ModelDistributionVersion?) {
        _pendingNotificationPermissionVersion.value = version
    }

    private val _pendingRoutingModeSwitch = MutableStateFlow<Pair<String, String>?>(null)
    val pendingRoutingModeSwitch = _pendingRoutingModeSwitch.asStateFlow()

    fun setPendingRoutingModeSwitch(value: Pair<String, String>?) {
        _pendingRoutingModeSwitch.value = value
    }

    private val _lastDownloadTransitionRefreshKey = MutableStateFlow<String?>(null)
    val lastDownloadTransitionRefreshKey = _lastDownloadTransitionRefreshKey.asStateFlow()

    fun setLastDownloadTransitionRefreshKey(key: String?) {
        _lastDownloadTransitionRefreshKey.value = key
    }

    private val _readinessRefreshSequence = MutableStateFlow(0L)
    val readinessRefreshSequence = _readinessRefreshSequence.asStateFlow()

    fun incrementReadinessRefreshSequence(): Long {
        _readinessRefreshSequence.value += 1L
        return _readinessRefreshSequence.value
    }
}
