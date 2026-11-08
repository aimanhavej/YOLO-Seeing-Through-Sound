
package com.aiman.yolo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            YOLOCameraApp()
        }
    }
}

@Composable
fun YOLOCameraApp() {

    val context = LocalContext.current

    var modelStatus by remember {
        mutableStateOf("Loading YOLO model...")
    }

    val yoloDetector: YOLODetector? = remember {
        try {
            val detector = YOLODetector(context)
            modelStatus = "YOLO model loaded successfully"
            detector
        } catch (exception: Exception) {
            modelStatus =
                "Model loading failed: ${exception.message}"
            null
        }
    }

    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var detectedObject by remember {
        mutableStateOf("Waiting for camera...")
    }

    // TEXT-TO-SPEECH
    var speechEngine by remember {
        mutableStateOf<TextToSpeech?>(null)
    }

    var ttsReady by remember {
        mutableStateOf(false)
    }

    // Remember the last object spoken
    var lastSpokenLabel by remember {
        mutableStateOf("")
    }

    DisposableEffect(context) {

        lateinit var engine: TextToSpeech

        engine = TextToSpeech(context) { status ->

            if (status == TextToSpeech.SUCCESS) {

                val languageResult =
                    engine.setLanguage(Locale.US)

                if (
                    languageResult != TextToSpeech.LANG_MISSING_DATA &&
                    languageResult != TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    ttsReady = true
                    Log.i("YOLO_TTS", "Speech engine ready")
                } else {
                    Log.e("YOLO_TTS", "English language unavailable")
                }

            } else {
                Log.e("YOLO_TTS", "Speech engine initialization failed")
            }
        }

        speechEngine = engine

        onDispose {
            engine.stop()
            engine.shutdown()
            speechEngine = null
            ttsReady = false
        }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            cameraPermissionGranted = isGranted
        }

    MaterialTheme {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Text(
                text = "YOLO",
                style = MaterialTheme.typography.headlineLarge
            )

            Text(
                text = "SEEING THROUGH SOUND",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (cameraPermissionGranted) {

                CameraPreview(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),

                    yoloDetector = yoloDetector,

                    onDetection = { result ->

                        detectedObject = result

                        // Extract the object name
                        val label =
                            if (result.startsWith("Detected: ")) {
                                result
                                    .removePrefix("Detected: ")
                                    .substringBefore(" (")
                            } else {
                                ""
                            }

                        // Speak only when a new object appears
                        if (label.isNotBlank()) {

                            if (
                                ttsReady &&
                                label != lastSpokenLabel
                            ) {

                                speechEngine?.speak(
                                    "$label detected",
                                    TextToSpeech.QUEUE_FLUSH,
                                    null,
                                    "YOLO_OBJECT"
                                )

                                lastSpokenLabel = label
                            }

                        } else {

                            // Allow the same object to be announced
                            // again if it disappears and returns.
                            lastSpokenLabel = ""
                        }
                    }
                )

            } else {

                Spacer(modifier = Modifier.height(40.dp))

                Text("Camera permission is needed.")

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        permissionLauncher.launch(
                            Manifest.permission.CAMERA
                        )
                    }
                ) {
                    Text("Allow Camera")
                }

                Spacer(modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = modelStatus,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = detectedObject,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }

    DisposableEffect(yoloDetector) {
        onDispose {
            yoloDetector?.close()
        }
    }
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    yoloDetector: YOLODetector?,
    onDetection: (String) -> Unit
) {

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val analysisExecutor = remember {
        Executors.newSingleThreadExecutor()
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(analysisExecutor) {
        onDispose {
            analysisExecutor.shutdown()
        }
    }

    DisposableEffect(lifecycleOwner, yoloDetector) {

        var cameraProvider: ProcessCameraProvider? = null
        var disposed = false

        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({

            if (!disposed) {

                try {

                    cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder()
                        .build()
                        .also {
                            it.surfaceProvider =
                                previewView.surfaceProvider
                        }

                    val imageAnalysis =
                        ImageAnalysis.Builder()
                            .setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                            )
                            .build()

                    imageAnalysis.setAnalyzer(
                        analysisExecutor
                    ) { imageProxy ->

                        try {

                            if (yoloDetector != null) {

                                val bitmap =
                                    imageProxy.toBitmap()

                                val rotatedBitmap =
                                    rotateBitmap(
                                        bitmap,
                                        imageProxy.imageInfo.rotationDegrees
                                    )

                                val detections =
                                    yoloDetector.detect(rotatedBitmap)

                                val result =
                                    if (detections.isNotEmpty()) {

                                        val best = detections.first()

                                        "Detected: ${best.label} " +
                                                "(${(best.confidence * 100).toInt()}%)"

                                    } else {
                                        "No object detected"
                                    }

                                ContextCompat.getMainExecutor(context)
                                    .execute {
                                        onDetection(result)
                                    }

                                if (rotatedBitmap !== bitmap) {
                                    rotatedBitmap.recycle()
                                }

                                bitmap.recycle()
                            }

                        } catch (exception: Exception) {

                            Log.e(
                                "YOLO_CAMERA",
                                "Frame processing failed",
                                exception
                            )

                        } finally {
                            imageProxy.close()
                        }
                    }

                    cameraProvider?.unbindAll()

                    cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )

                    Log.i(
                        "YOLO_CAMERA",
                        "Camera preview and analysis started"
                    )

                } catch (exception: Exception) {

                    Log.e(
                        "YOLO_CAMERA",
                        "Camera setup failed",
                        exception
                    )
                }
            }

        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            cameraProvider?.unbindAll()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

private fun rotateBitmap(
    bitmap: Bitmap,
    degrees: Int
): Bitmap {

    if (degrees == 0) {
        return bitmap
    }

    val matrix = Matrix().apply {
        postRotate(degrees.toFloat())
    }

    return Bitmap.createBitmap(
        bitmap,
        0,
        0,
        bitmap.width,
        bitmap.height,
        matrix,
        true
    )
}

private fun ImageProxy.toBitmap(): Bitmap {

    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer

    val width = width
    val height = height

    val yRowStride = yPlane.rowStride
    val uRowStride = uPlane.rowStride
    val vRowStride = vPlane.rowStride

    val yPixelStride = yPlane.pixelStride
    val uPixelStride = uPlane.pixelStride
    val vPixelStride = vPlane.pixelStride

    val pixels = IntArray(width * height)

    for (row in 0 until height) {

        for (col in 0 until width) {

            val yIndex =
                row * yRowStride + col * yPixelStride

            val uvRow = row / 2
            val uvCol = col / 2

            val uIndex =
                uvRow * uRowStride + uvCol * uPixelStride

            val vIndex =
                uvRow * vRowStride + uvCol * vPixelStride

            val y =
                (yBuffer.get(yIndex).toInt() and 0xFF) - 16

            val u =
                (uBuffer.get(uIndex).toInt() and 0xFF) - 128

            val v =
                (vBuffer.get(vIndex).toInt() and 0xFF) - 128

            val yValue = (y.coerceAtLeast(0) * 298)

            val red =
                ((yValue + 409 * v + 128) shr 8)
                    .coerceIn(0, 255)

            val green =
                ((yValue - 100 * u - 208 * v + 128) shr 8)
                    .coerceIn(0, 255)

            val blue =
                ((yValue + 516 * u + 128) shr 8)
                    .coerceIn(0, 255)

            pixels[row * width + col] =
                Color.rgb(red, green, blue)
        }
    }

    return Bitmap.createBitmap(
        pixels,
        width,
        height,
        Bitmap.Config.ARGB_8888
    )
}
