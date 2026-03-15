package com.pocketagent.android.runtime.modelmanager

import android.net.Network
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

internal object DownloadHttpClient {
    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun base(): OkHttpClient = baseClient

    fun forNetwork(network: Network?): OkHttpClient {
        if (network == null) {
            return baseClient
        }
        return baseClient.newBuilder()
            .socketFactory(network.socketFactory)
            .build()
    }
}
