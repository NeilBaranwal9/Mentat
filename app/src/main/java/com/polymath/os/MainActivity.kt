package com.polymath.os

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.polymath.os.ui.nav.PolymathRoot
import com.polymath.os.ui.theme.PolymathTheme
import dagger.hilt.android.AndroidEntryPoint

/** No business logic or screen state here (Rule 3). Rotation is handled by ViewModels, not configChanges. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val destination = intent?.getStringExtra(EXTRA_DESTINATION)
        setContent {
            PolymathTheme {
                PolymathRoot(launchDestination = destination)
            }
        }
    }

    companion object {
        const val EXTRA_DESTINATION = "destination"
    }
}
