package org.mobilenativefoundation.store6.devtoolsdemo

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

// Temporary T1 host used only to compile each demo target.
@Composable
internal fun DevtoolsDemoApp() {
    MaterialTheme {
        Column {
            Text("Store6 devtools")
        }
    }
}
