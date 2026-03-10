package com.pocketagent.android.runtime.modelmanager

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object ModelInstallIo {
    fun replaceWithAtomicMove(source: File, destination: File): Boolean {
        destination.parentFile?.mkdirs()
        val sourcePath = source.toPath()
        val destinationPath = destination.toPath()
        val moved = runCatching {
            Files.move(
                sourcePath,
                destinationPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        }.recoverCatching { error ->
            if (error !is AtomicMoveNotSupportedException) {
                throw error
            }
            Files.move(
                sourcePath,
                destinationPath,
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        }.getOrElse { false }
        if (moved) {
            syncFile(destination)
        }
        return moved
    }

    fun syncFile(file: File) {
        if (!file.exists()) {
            return
        }
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.fd.sync()
            }
        }
    }
}
