package com.pocketagent.runtime

import com.pocketagent.core.PolicyModule
import com.pocketagent.tools.ToolCall
import com.pocketagent.tools.ToolModule
import com.pocketagent.tools.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolExecutionUseCaseTest {
    @Test
    fun `returns policy denied failure when tool event is blocked`() {
        val useCase = ToolExecutionUseCase(
            policyModule = ToolPolicyModule(allowedEvents = emptySet()),
            toolLoopCoordinator = ToolLoopCoordinator(ToolTestModule(ToolResult(success = true, content = "ok"))),
        )

        val result = useCase.execute(toolName = "calculator", jsonArgs = """{"expression":"1+1"}""")

        assertTrue(result is ToolExecutionResult.Failure)
        val failure = (result as ToolExecutionResult.Failure).failure
        assertTrue(failure is ToolFailure.PolicyDenied)
        assertEquals("tool_policy_denied", failure.code)
    }

    @Test
    fun `returns typed validation failure for schema validation errors`() {
        val useCase = ToolExecutionUseCase(
            policyModule = ToolPolicyModule(allowedEvents = setOf("tool.execute")),
            toolLoopCoordinator = ToolLoopCoordinator(
                ToolTestModule(
                    ToolResult(
                        success = false,
                        content = "ignored",
                        validationErrorCode = "INVALID_FIELD_VALUE",
                        validationErrorDetail = "expression rejected",
                    ),
                ),
            ),
        )

        val result = useCase.execute(toolName = "calculator", jsonArgs = """{"expression":"bad"}""")

        assertTrue(result is ToolExecutionResult.Failure)
        val failure = (result as ToolExecutionResult.Failure).failure
        assertTrue(failure is ToolFailure.Validation)
        assertEquals("invalid_field_value", failure.code)
        assertEquals("expression rejected", failure.technicalDetail)
    }

    @Test
    fun `maps legacy validation prefix to typed validation failure`() {
        val useCase = ToolExecutionUseCase(
            policyModule = ToolPolicyModule(allowedEvents = setOf("tool.execute")),
            toolLoopCoordinator = ToolLoopCoordinator(
                ToolTestModule(
                    ToolResult(
                        success = false,
                        content = "TOOL_VALIDATION_ERROR:INVALID_FIELD_VALUE:legacy detail",
                    ),
                ),
            ),
        )

        val result = useCase.execute(toolName = "calculator", jsonArgs = """{"expression":"bad"}""")

        assertTrue(result is ToolExecutionResult.Failure)
        val failure = (result as ToolExecutionResult.Failure).failure
        assertTrue(failure is ToolFailure.Validation)
        assertEquals("invalid_field_value", failure.code)
        assertEquals("legacy detail", failure.technicalDetail)
    }

    @Test
    fun `maps generic tool failure to execution failure`() {
        val useCase = ToolExecutionUseCase(
            policyModule = ToolPolicyModule(allowedEvents = setOf("tool.execute")),
            toolLoopCoordinator = ToolLoopCoordinator(
                ToolTestModule(
                    ToolResult(
                        success = false,
                        content = "unexpected bridge fault",
                    ),
                ),
            ),
        )

        val result = useCase.execute(toolName = "calculator", jsonArgs = "{}")

        assertTrue(result is ToolExecutionResult.Failure)
        val failure = (result as ToolExecutionResult.Failure).failure
        assertTrue(failure is ToolFailure.Execution)
        assertEquals("tool_runtime_error", failure.code)
        assertEquals("unexpected bridge fault", failure.technicalDetail)
    }
}

private class ToolTestModule(
    private val toolResult: ToolResult,
) : ToolModule {
    override fun listEnabledTools(): List<String> = listOf("calculator")

    override fun validateToolCall(call: ToolCall): Boolean = true

    override fun executeToolCall(call: ToolCall): ToolResult = toolResult
}

private class ToolPolicyModule(
    private val allowedEvents: Set<String>,
) : PolicyModule {
    override fun isNetworkAllowedForAction(action: String): Boolean = false

    override fun getRetentionWindowDays(): Int = 30

    override fun enforceDataBoundary(eventType: String): Boolean = allowedEvents.contains(eventType)
}
