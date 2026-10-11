package dev.iyas.ninilala.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.18f))

        Image(
            painter = painterResource(R.drawable.splash_screen),
            contentDescription = null,
            modifier = Modifier
                .size(168.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop,
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.home_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurface.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        Spacer(Modifier.weight(0.16f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ImageActionButton(
                illustration = R.drawable.ill_btn_stream,
                label = stringResource(R.string.home_stream),
                hint = stringResource(R.string.home_stream_hint),
                accent = scheme.primary,
                onClick = onStream,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            ImageActionButton(
                illustration = R.drawable.ill_btn_watch,
                label = stringResource(R.string.home_watch),
                hint = stringResource(R.string.home_watch_hint),
                accent = scheme.secondary,
                onClick = onWatch,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }

        Spacer(Modifier.weight(0.22f))
    }
}

@Composable
fun ImageActionButton(
    @DrawableRes illustration: Int,
    label: String,
    hint: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(140),
        label = "imageActionScale",
    )
    val shape = MaterialTheme.shapes.large

    Column(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(scheme.surface)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = accent.copy(alpha = 0.28f)),
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(illustration),
            contentDescription = label,
            modifier = Modifier.size(80.dp),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = accent,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }
}

@Composable
fun SoftActionCard(
    label: String,
    hint: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes illustration: Int? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = tween(140),
        label = "softActionScale",
    )
    val shape = MaterialTheme.shapes.medium

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(shape)
            .background(scheme.surface)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = accent.copy(alpha = 0.25f)),
                onClick = onClick,
            )
            .padding(end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(6.dp)
                .height(if (illustration != null) 84.dp else 72.dp)
                .background(accent),
        )
        if (illustration != null) {
            Image(
                painter = painterResource(illustration),
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(56.dp),
                contentScale = ContentScale.Fit,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp, top = 18.dp, bottom = 18.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = accent,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
