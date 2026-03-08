package com.pocketagent.android.testutil

import android.net.Uri
import android.net.TestUri

fun fakeUri(value: String = "content://pocketagent/test"): Uri {
    return TestUri(value)
}
