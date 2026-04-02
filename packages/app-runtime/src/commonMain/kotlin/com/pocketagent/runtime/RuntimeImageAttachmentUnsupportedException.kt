package com.pocketagent.runtime

class RuntimeImageAttachmentUnsupportedException(
    message: String,
    val errorCode: String = "image_attachments_unsupported",
) : RuntimeException(message)
