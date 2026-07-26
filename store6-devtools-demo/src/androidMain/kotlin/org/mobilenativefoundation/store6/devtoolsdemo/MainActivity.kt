package org.mobilenativefoundation.store6.devtoolsdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

// Temporary T1 Android host used only to prove application-plugin assembly.
internal class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DevtoolsDemoApp()
        }
    }
}
