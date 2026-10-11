package dev.iyas.ninilala.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.iyas.ninilala.R

@Composable
fun CircleIconButton(
    painter: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val scheme = MaterialTheme.colorScheme
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(scheme.surface.copy(alpha = 0.92f)),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = tint,
            containerColor = Color.Transparent,
        ),
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            tint = tint,
        )
    }
}

@Composable
fun BackIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CircleIconButton(
        painter = painterResource(R.drawable.ic_arrow_back),
        contentDescription = stringResource(R.string.action_back),
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
fun CameraSwitchIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CircleIconButton(
        painter = painterResource(R.drawable.ic_cameraswitch),
        contentDescription = stringResource(R.string.action_switch_camera),
        onClick = onClick,
        modifier = modifier,
    )
}
