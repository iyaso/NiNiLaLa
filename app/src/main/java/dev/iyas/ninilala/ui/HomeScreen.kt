package dev.iyas.ninilala.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.iyas.ninilala.R

@Composable
fun HomeScreen(
    onStream: () -> Unit,
    onWatch: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = scheme.onBackground,
        )
        Text(
            text = stringResource(R.string.home_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(40.dp))

        HomeAction(
            label = stringResource(R.string.home_stream),
            hint = stringResource(R.string.home_stream_hint),
            accent = scheme.primary,
            onClick = onStream,
        )
        Spacer(Modifier.height(12.dp))
        HomeAction(
            label = stringResource(R.string.home_watch),
            hint = stringResource(R.string.home_watch_hint),
            accent = scheme.secondary,
            onClick = onWatch,
        )
    }
}

@Composable
private fun HomeAction(
    label: String,
    hint: String,
    accent: Color,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = tween(140),
        label = "homeActionScale",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(22.dp))
            .background(scheme.surfaceVariant)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = accent.copy(alpha = 0.25f)),
                onClick = onClick,
            )
            .padding(horizontal = 22.dp, vertical = 20.dp),
    ) {
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = accent,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
