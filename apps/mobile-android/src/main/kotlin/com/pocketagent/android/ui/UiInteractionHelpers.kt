package com.pocketagent.android.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.pocketagent.android.ui.theme.tickLight

internal fun Modifier.disclosureSemantics(
    label: String,
    stateLabel: String,
): Modifier {
    return semantics {
        contentDescription = label
        stateDescription = stateLabel
        role = Role.Button
    }
}

internal inline fun HapticFeedback.tickLightThen(action: () -> Unit) {
    tickLight()
    action()
}
