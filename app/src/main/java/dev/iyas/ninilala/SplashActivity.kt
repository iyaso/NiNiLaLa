package dev.iyas.ninilala

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.iyas.ninilala.ui.theme.NiNiLaLaTheme
import kotlinx.coroutines.delay

class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isTaskRoot) {
            finish()
            return
        }

        enableEdgeToEdge()

        setContent {
            NiNiLaLaTheme {
                val scheme = MaterialTheme.colorScheme
                val infiniteTransition = rememberInfiniteTransition(label = "splash")
                val rotation by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "spinnerRotation",
                )

                LaunchedEffect(Unit) {
                    delay(1500)
                    val intent = Intent(this@SplashActivity, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                    startActivity(intent)
                    finish()
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(scheme.background),
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.splash_screen),
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(200.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop,
                    )

                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 48.dp)
                            .size(40.dp)
                            .rotate(rotation),
                        color = scheme.onBackground,
                        strokeWidth = 3.dp,
                    )
                }
            }
        }
    }
}
