package dev.iyas.ninilala.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.iyas.ninilala.R
import dev.iyas.ninilala.watch.DiscoveredStream
import dev.iyas.ninilala.watch.LanDiscovery
import dev.iyas.ninilala.watch.PlayerConnectionState
import dev.iyas.ninilala.watch.StreamPlayer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

@Composable
fun WatchScreen(
    onBack: () -> Unit,
    onSelect: (DiscoveredStream) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val discovery = remember { LanDiscovery(context) }
    val streamsFlow = remember(discovery, scope) {
        discovery.discover().stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }
    val streams by streamsFlow.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackIconButton(onClick = onBack)
            Text(
                text = stringResource(R.string.home_watch),
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onBackground,
                modifier = Modifier.padding(start = 14.dp),
            )
        }

        if (streams.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.watch_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(streams, key = { "${it.host}:${it.port}:${it.name}" }) { stream ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(scheme.surfaceVariant)
                            .clickable { onSelect(stream) }
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                    ) {
                        Text(
                            text = stream.name,
                            style = MaterialTheme.typography.titleLarge,
                            color = scheme.onBackground,
                        )
                        Text(
                            text = "${stream.host}:${stream.port}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.primary.copy(alpha = 0.85f),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlayingScreen(
    stream: DiscoveredStream,
    onClose: () -> Unit,
) {
    val player = remember { StreamPlayer() }
    val connectionState by player.connectionState.collectAsStateWithLifecycle()

    DisposableEffect(stream.host, stream.port) {
        onDispose { player.stop() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            player.start(stream.host, stream.port, holder.surface)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            player.stop()
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        val statusText = when (connectionState) {
            PlayerConnectionState.Connecting -> stringResource(R.string.status_connecting)
            PlayerConnectionState.Reconnecting -> stringResource(R.string.status_reconnecting)
            PlayerConnectionState.Failed -> stringResource(R.string.status_failed)
            else -> null
        }

        if (statusText != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
            }
        }

        BackIconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp),
        )
    }
}
