package com.pocketagent.nativebridge

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessCommandRunnerTest {
    @Test
    fun `run does not deadlock when stderr is large`() {
        val runner = ProcessCommandRunner()
        val command = listOf(
            "bash",
            "-lc",
            "i=0; while [ \$i -lt 50000 ]; do echo errline 1>&2; i=\$((i+1)); done",
        )

        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<CommandResult> { runner.run(command) }
            val result = future.get(10, TimeUnit.SECONDS)
            assertEquals(0, result.exitCode)
            assertTrue(result.stderr.contains("errline"))
        } finally {
            executor.shutdownNow()
        }
    }
}
