package com.pocketagent.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkPolicyManifestConfigTest {
    @Test
    fun `manifest and network security config enforce offline-safe defaults`() {
        val repoRoot = findRepoRoot(File(System.getProperty("user.dir") ?: "."))
        val manifest = File(repoRoot, "apps/mobile-android/src/main/AndroidManifest.xml").readText()
        val networkConfig = File(repoRoot, "apps/mobile-android/src/main/res/xml/network_security_config.xml").readText()

        assertFalse(manifest.contains("uses-permission android:name=\"android.permission.INTERNET\""))
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"false\""))
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertTrue(networkConfig.contains("cleartextTrafficPermitted=\"false\""))
    }

    @Test
    fun `internal download flavor manifest scopes internet permission`() {
        val repoRoot = findRepoRoot(File(System.getProperty("user.dir") ?: "."))
        val mainManifest = File(repoRoot, "apps/mobile-android/src/main/AndroidManifest.xml").readText()
        val internalManifest = File(repoRoot, "apps/mobile-android/src/internalDownload/AndroidManifest.xml").readText()

        assertFalse(mainManifest.contains("uses-permission android:name=\"android.permission.INTERNET\""))
        assertTrue(internalManifest.contains("uses-permission android:name=\"android.permission.INTERNET\""))
    }

    private fun findRepoRoot(start: File): File {
        var cursor: File? = start.absoluteFile
        while (cursor != null) {
            val hasSettings = File(cursor, "settings.gradle.kts").exists()
            val hasAppModule = File(cursor, "apps/mobile-android").exists()
            if (hasSettings && hasAppModule) {
                return cursor
            }
            cursor = cursor.parentFile
        }
        error("Failed to resolve repository root from ${start.absolutePath}")
    }
}
