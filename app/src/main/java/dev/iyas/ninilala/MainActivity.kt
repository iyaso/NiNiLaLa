package dev.iyas.ninilala

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.iyas.ninilala.ui.Destination
import dev.iyas.ninilala.ui.HomeScreen
import dev.iyas.ninilala.ui.PlayingScreen
import dev.iyas.ninilala.ui.StreamScreen
import dev.iyas.ninilala.ui.WatchScreen
import dev.iyas.ninilala.ui.theme.NiNiLaLaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            NiNiLaLaTheme {
                val isDark = isSystemInDarkTheme()
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !isDark
                        isAppearanceLightNavigationBars = !isDark
                    }
                }
                Surface(modifier = Modifier.fillMaxSize()) {
                    var destination by remember { mutableStateOf<Destination>(Destination.Home) }

                    BackHandler(enabled = destination !is Destination.Home) {
                        destination = when (destination) {
                            is Destination.Playing -> Destination.Watch
                            else -> Destination.Home
                        }
                    }

                    AnimatedContent(
                        targetState = destination,
                        transitionSpec = {
                            (fadeIn(tween(280)) togetherWith fadeOut(tween(200)))
                                .using(SizeTransform(clip = false))
                        },
                        label = "rootNav",
                    ) { dest ->
                        when (dest) {
                            Destination.Home -> HomeScreen(
                                onStream = { destination = Destination.Stream },
                                onWatch = { destination = Destination.Watch },
                            )
                            Destination.Stream -> StreamScreen(
                                onBack = { destination = Destination.Home },
                            )
                            Destination.Watch -> WatchScreen(
                                onBack = { destination = Destination.Home },
                                onSelect = { destination = Destination.Playing(it) },
                            )
                            is Destination.Playing -> PlayingScreen(
                                stream = dest.stream,
                                onClose = { destination = Destination.Watch },
                            )
                        }
                    }
                }
            }
        }
    }
}
