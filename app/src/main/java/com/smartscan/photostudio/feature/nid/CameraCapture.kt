package com.smartscan.photostudio.feature.nid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.smartscan.photostudio.ui.components.PrimaryActionButton
import com.smartscan.photostudio.ui.components.SecondaryActionButton
import com.smartscan.photostudio.ui.theme.CardShape
import com.smartscan.photostudio.ui.theme.ComponentStyles
import com.smartscan.photostudio.ui.theme.Spacing
import java.io.File

/** The on-screen frame: card is cropped to exactly this area after capture. */
const val FRAME_WIDTH_FRACTION = 0.86f
const val CARD_ASPECT = 1.586f

@Composable
fun CameraCapture(
    onCaptured: (File) -> Unit,
    onGallery: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    frameAspect: Float = CARD_ASPECT,
    hint: String = "Place the card inside the frame",
    allowFlip: Boolean = false,
) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok; denied = !ok
    }

    if (!granted) {
        Card(shape = CardShape, colors = ComponentStyles.cardColors(), modifier = modifier.fillMaxWidth()) {
            Column(Modifier.padding(Spacing.x4), verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
                Text("Camera access needed", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The camera is used only to photograph your card. Photos are processed on this device and are never uploaded.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (denied) {
                    Text("Permission was denied. You can allow it in app settings, or choose a photo from your gallery instead.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                PrimaryActionButton("Allow camera", { launcher.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth())
                if (denied) {
                    SecondaryActionButton("Open app settings", {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                    }, Modifier.fillMaxWidth())
                }
                SecondaryActionButton("Choose from gallery", onGallery, Modifier.fillMaxWidth())
            }
        }
        return
    }
    LiveCamera(onCaptured, onGallery, onError, modifier, frameAspect, hint, allowFlip)
}

@Composable
private fun LiveCamera(
    onCaptured: (File) -> Unit,
    onGallery: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
    frameAspect: Float,
    hint: String,
    allowFlip: Boolean,
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var taking by remember { mutableStateOf(false) }
    var front by remember { mutableStateOf(false) }
    val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true

    DisposableEffect(lifecycleOwner, front) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(lifecycleOwner, (if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA), preview, imageCapture)
            } catch (e: Exception) {
                onError("Could not start the camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { runCatching { future.get().unbindAll() } }
    }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(3f / 4f)
                .background(Color.Black, RoundedCornerShape(20.dp)),
        ) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width * FRAME_WIDTH_FRACTION
                val h = w / frameAspect
                drawRoundRect(
                    Color.White, Offset((size.width - w) / 2f, (size.height - h) / 2f), Size(w, h),
                    CornerRadius(12.dp.toPx()), style = Stroke(3.dp.toPx()),
                )
            }
            Text(
                hint,
                color = Color.White, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.TopCenter).padding(Spacing.x3),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onGallery, modifier = Modifier.size(Spacing.minTouchTarget)) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = "Import from gallery")
            }
            Box(
                Modifier.size(72.dp)
                    .border(4.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(6.dp)
                    .background(if (taking) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary, CircleShape)
                    .clickable(enabled = !taking, onClickLabel = "Capture") {
                        taking = true
                        val file = File.createTempFile("cap_", ".jpg", ctx.cacheDir)
                        imageCapture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(file).build(),
                            ContextCompat.getMainExecutor(ctx),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(r: ImageCapture.OutputFileResults) { taking = false; onCaptured(file) }
                                override fun onError(e: ImageCaptureException) { taking = false; onError("Capture failed: ${e.message}") }
                            },
                        )
                    },
            )
            if (allowFlip) IconButton(onClick = { front = !front }, modifier = Modifier.size(Spacing.minTouchTarget)) {
                Icon(Icons.Filled.FlipCameraAndroid, contentDescription = "Switch camera")
            }
            IconButton(onClick = { torch = !torch }, enabled = hasFlash, modifier = Modifier.size(Spacing.minTouchTarget)) {
                Icon(if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff, contentDescription = "Toggle flash")
            }
        }
    }
}
