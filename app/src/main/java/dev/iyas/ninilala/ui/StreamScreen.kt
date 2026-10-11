package dev.iyas.ninilala.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.iyas.ninilala.R
import dev.iyas.ninilala.stream.PreviewCamera
import dev.iyas.ninilala.stream.PreviewProfile
import dev.iyas.ninilala.stream.StreamService

@Composable
fun StreamScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val streaming by StreamService.running.collectAsStateWithLifecycle()
    var useFront by remember { mutableStateOf(false) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val previewCamera = remember { PreviewCamera(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasCameraPermission = result[Manifest.permission.CAMERA] == true
    }

    LaunchedEffect(Unit) {
        val needed = buildList {
            if (!hasCameraPermission) add(Manifest.permission.CAMERA)
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    DisposableEffect(streaming) {
        val window = activity?.window
        val originalBrightness = window?.attributes?.screenBrightness
        if (streaming) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.attributes?.apply {
                screenBrightness = 0.1f
                window?.attributes = this
            }
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            originalBrightness?.let { brightness ->
                window?.attributes?.apply {
                    screenBrightness = brightness
                    window?.attributes = this
                }
            }
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            originalBrightness?.let { brightness ->
                window?.attributes?.apply {
                    screenBrightness = brightness
                    window?.attributes = this
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { previewCamera.stop() }
    }

    val scheme = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        AnimatedContent(
            targetState = streaming,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "streamState",
        ) { isStreaming ->
            if (isStreaming) {
                StreamingPanel(onStop = { StreamService.stop(context) })
            } else {
                PreviewPanel(
                    hasCameraPermission = hasCameraPermission,
                    useFront = useFront,
                    onToggleCamera = { useFront = !useFront },
                    onStart = {
                        previewCamera.stop()
                        StreamService.start(context, useFront)
                    },
                    onBack = onBack,
                    onRequestPermission = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.CAMERA,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ),
                        )
                    },
                    previewCamera = previewCamera,
                )
            }
        }
    }
}

@Composable
private fun PreviewPanel(
    hasCameraPermission: Boolean,
    useFront: Boolean,
    onToggleCamera: () -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
    previewCamera: PreviewCamera,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackIconButton(onClick = onBack)
            if (hasCameraPermission) {
                CameraSwitchIconButton(onClick = onToggleCamera)
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (hasCameraPermission) {
                FittedCameraPreview(
                    useFront = useFront,
                    previewCamera = previewCamera,
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp),
                ) {
                    Text(
                        text = stringResource(R.string.camera_permission_needed),
                        color = scheme.onSurface.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onRequestPermission,
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = scheme.primary,
                            contentColor = scheme.onPrimary,
                        ),
                    ) {
                        Text(
                            text = stringResource(R.string.action_allow_camera),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }

        if (hasCameraPermission) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 28.dp, top = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Button(
                    onClick = onStart,
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.primary,
                        contentColor = scheme.onPrimary,
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                ) {
                    Text(
                        text = stringResource(R.string.action_start),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun FittedCameraPreview(
    useFront: Boolean,
    previewCamera: PreviewCamera,
) {
    val profile = remember(useFront) {
        previewCamera.resolveProfile(useFront)
            ?: PreviewProfile(Size(1280, 720), 9f / 16f)
    }
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val parentW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val parentH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val parentAspect = parentW / parentH
        val targetAspect = profile.displayAspectRatio

        val (fitW, fitH) = if (parentAspect > targetAspect) {
            // Parent is wider — fit to height (pillarbox).
            val h = parentH
            val w = h * targetAspect
            w to h
        } else {
            // Parent is taller — fit to width (letterbox).
            val w = parentW
            val h = w / targetAspect
            w to h
        }

        androidx.compose.runtime.key(useFront) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.setFixedSize(profile.bufferSize.width, profile.bufferSize.height)
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                previewCamera.start(
                                    useFront = useFront,
                                    surface = holder.surface,
                                    bufferSize = profile.bufferSize,
                                )
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int,
                            ) = Unit

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                previewCamera.stop()
                            }
                        })
                    }
                },
                modifier = Modifier
                    .width(with(density) { fitW.toDp() })
                    .height(with(density) { fitH.toDp() })
                    .clip(RoundedCornerShape(20.dp)),
            )
        }
    }
}

@Composable
private fun StreamingPanel(onStop: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val hasViewer by StreamService.viewerConnected.collectAsStateWithLifecycle()
    val webUrl by StreamService.webUrl.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (hasViewer) scheme.primary else scheme.onSurface.copy(alpha = 0.3f)),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.streaming_label),
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurface.copy(alpha = 0.5f),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        if (hasViewer) R.string.status_viewer_connected
                        else R.string.status_no_viewer
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface.copy(alpha = 0.4f),
                )
            }
        }

        if (webUrl != null) {
            Spacer(Modifier.height(32.dp))
            WebViewerQr(url = webUrl!!)
        }

        Spacer(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Button(
                onClick = onStop,
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.surfaceVariant,
                    contentColor = scheme.onSurfaceVariant,
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            ) {
                Text(
                    text = stringResource(R.string.action_stop),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun WebViewerQr(url: String) {
    val scheme = MaterialTheme.colorScheme
    val qrBitmap = remember(url) { generateQrBitmap(url) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.web_viewer_qr_desc),
                modifier = Modifier
                    .size(170.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
            Spacer(Modifier.height(12.dp))
        }
        Text(
            text = url,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.web_viewer_hint),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurface.copy(alpha = 0.45f),
        )
    }
}

private fun generateQrBitmap(content: String): Bitmap? = runCatching {
    val size = 512
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(EncodeHintType.MARGIN to 1),
    )
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val offset = y * size
        for (x in 0 until size) {
            pixels[offset + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}.getOrNull()
