package dev.iyas.ninilala.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexvas.rtsp.RtspClient
import com.alexvas.rtsp.widget.RtspStatusListener
import com.alexvas.rtsp.widget.RtspSurfaceView
import dev.iyas.ninilala.R
import dev.iyas.ninilala.watch.DiscoveredStream
import dev.iyas.ninilala.watch.LanDiscovery
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
    val connecting = stringResource(R.string.status_connecting)
    val reconnecting = stringResource(R.string.status_reconnecting)
    var statusText by remember { mutableStateOf<String?>(null) }
    var videoSize by remember { mutableStateOf<IntSize?>(null) }
    val alive = remember { AtomicBoolean(true) }
    val player = remember { mutableStateOf<RtspSurfaceView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            alive.set(false)
            player.value?.stop()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val parentW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val parentH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val parentAspect = parentW / parentH
        val size = videoSize?.takeIf { it.width > 0 && it.height > 0 }

        val viewModifier: Modifier = if (size == null) {
            Modifier.fillMaxSize()
        } else {
            val videoAspect = size.width.toFloat() / size.height.toFloat()
            val (fitW, fitH) = if (parentAspect > videoAspect) {
                parentH * videoAspect to parentH
            } else {
                parentW to parentW / videoAspect
            }
            Modifier
                .width(with(density) { fitW.toDp() })
                .height(with(density) { fitH.toDp() })
        }

        AndroidView(
            factory = { ctx ->
                RtspSurfaceView(ctx).apply {
                    player.value = this
                    val uri = Uri.parse("rtsp://${stream.host}:${stream.port}/stream")
                    fun open() {
                        if (!alive.get()) return
                        init(
                            uri = uri,
                            username = null,
                            password = null,
                            userAgent = "NiNiLaLa",
                            socketTimeout = 5000,
                            transport = RtspClient.Transport.TCP,
                        )
                        start(requestVideo = true, requestAudio = true, requestApplication = false)
                    }
                    setStatusListener(object : RtspStatusListener {
                        override fun onRtspStatusConnecting() {
                            statusText = connecting
                        }

                        override fun onRtspStatusConnected() {
                            statusText = null
                        }

                        override fun onRtspStatusDisconnected() {
                            statusText = reconnecting
                            postDelayed({ open() }, 1000)
                        }

                        override fun onRtspStatusFailed(message: String?) {
                            statusText = reconnecting
                            postDelayed({ open() }, 1000)
                        }

                        override fun onRtspFrameSizeChanged(width: Int, height: Int) {
                            videoSize = IntSize(width, height)
                        }
                    })
                    open()
                }
            },
            modifier = viewModifier,
        )

        if (statusText != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            ) {
                Text(
                    text = statusText!!,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
            }
        }

        BackIconButton(
            onClick = {
                onClose()
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp),
        )
    }
}
