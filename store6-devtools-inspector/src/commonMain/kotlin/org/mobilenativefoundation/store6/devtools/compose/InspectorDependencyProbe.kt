package org.mobilenativefoundation.store6.devtools.compose

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

// Temporary T1 compile probe; the planned inspector UI is introduced by later tasks.
@Composable
internal fun InspectorDependencyProbe() {
    val label by remember { mutableStateOf("Store6 devtools") }
    Row {
        Text(label)
    }
}
