package com.pocketagent.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeFailureContractsTest {
    @Test
    fun `tool legacy validation payload maps to typed validation failure`() {
        val mapped = ToolExecutionResult.fromLegacy("TOOL_VALIDATION_ERROR:INVALID_FIELD_VALUE:bad expression")

        assertTrue(mapped is ToolExecutionResult.Failure)
        val failure = (mapped as ToolExecutionResult.Failure).failure
        assertTrue(failure is ToolFailure.Validation)
        assertEquals("invalid_field_value", failure.code)
        assertEquals("bad expression", failure.technicalDetail)
    }

    @Test
    fun `tool validation failure round trips to legacy payload`() {
        val legacy = ToolExecutionResult.Failure(
            ToolFailure.Validation(
                code = "invalid_field_value",
                userMessage = "That tool request was rejected for safety.",
                technicalDetail = "Field expression is invalid.",
            ),
        ).toLegacyString()

        assertEquals(
            "TOOL_VALIDATION_ERROR:INVALID_FIELD_VALUE:Field expression is invalid.",
            legacy,
        )
    }

    @Test
    fun `image validation failure round trips to legacy payload`() {
        val legacy = ImageAnalysisResult.Failure(
            ImageFailure.Validation(
                code = "unsupported_extension",
                userMessage = "That image could not be processed.",
                technicalDetail = "extension 'tiff' is not supported",
            ),
        ).toLegacyString()

        assertEquals(
            "IMAGE_VALIDATION_ERROR:UNSUPPORTED_EXTENSION:extension 'tiff' is not supported",
            legacy,
        )
    }

    @Test
    fun `image legacy runtime payload maps to typed runtime failure`() {
        val mapped = ImageAnalysisResult.fromLegacy("IMAGE_RUNTIME_ERROR:IMAGE_RUNTIME_ERROR:bridge failed")

        assertTrue(mapped is ImageAnalysisResult.Failure)
        val failure = (mapped as ImageAnalysisResult.Failure).failure
        assertTrue(failure is ImageFailure.Runtime)
        assertEquals("image_runtime_error", failure.code)
        assertEquals("bridge failed", failure.technicalDetail)
    }
}
