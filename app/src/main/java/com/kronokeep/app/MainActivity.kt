package com.kronokeep.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kronokeep.app.ui.KronoKeepAppRoot
import com.kronokeep.app.ui.theme.KronoKeepTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KronoKeepTheme {
                KronoKeepAppRoot()
            }
        }
    }
}
