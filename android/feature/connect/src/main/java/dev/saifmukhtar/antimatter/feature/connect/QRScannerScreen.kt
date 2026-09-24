package dev.saifmukhtar.antimatter.feature.connect

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun QRScannerScreen(
    onQRScanned: (url: String, token: String, cfId: String?, cfSecret: String?, pubKey: String?) -> Unit,
    onNavigateBack: () -> Unit
) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val context = LocalContext.current
    var hasDetected by remember { mutableStateOf(false) }

    val handleBarcode = { barcodeValue: String ->
        if (!hasDetected) {
            hasDetected = true
            try {
                // Trigger quick haptic vibration pulse
                try {
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(50)
                    }
                } catch (_: Exception) {}

                val uri = Uri.parse(barcodeValue)
                val isAimonScheme = (uri.scheme == "aimon" && uri.host == "pair") ||
                                    (uri.scheme == "aimon" && uri.path == "/pair")
                val isAntimatterScheme = uri.scheme == "antimatter" && uri.host == "connect"
                val isHttpsScheme = uri.scheme == "https" && uri.host == "antimatter.saifmukhtar.dev" && uri.path == "/connect"
                
                if (isAimonScheme) {
                    val host = uri.getQueryParameter("host") ?: "127.0.0.1"
                    val port = uri.getQueryParameter("port") ?: "3883"
                    val secret = uri.getQueryParameter("secret") ?: ""
                    val url = "http://$host:$port"
                    onQRScanned(url, secret, null, null, null)
                } else if (isAntimatterScheme || isHttpsScheme) {
                    val url = uri.getQueryParameter("url")
                    val token = uri.getQueryParameter("token")
                    var cfId = uri.getQueryParameter("cfid")
                    var cfSecret = uri.getQueryParameter("cfsec")
                    val cfEnc = uri.getQueryParameter("cfenc")
                    val pubKey = uri.getQueryParameter("x25519_pub")

                    if (cfEnc != null && token != null) {
                        try {
                            val parts = cfEnc.split(":")
                            if (parts.size == 3) {
                                val iv = android.util.Base64.decode(parts[0], android.util.Base64.DEFAULT)
                                val authTag = android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT)
                                val ciphertext = android.util.Base64.decode(parts[2], android.util.Base64.DEFAULT)
                                
                                val md = java.security.MessageDigest.getInstance("SHA-256")
                                val keyBytes = md.digest(token.toByteArray(Charsets.UTF_8))
                                val keySpec = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
                                val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
                                
                                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, gcmSpec)
                                
                                val combined = ByteArray(ciphertext.size + authTag.size)
                                System.arraycopy(ciphertext, 0, combined, 0, ciphertext.size)
                                System.arraycopy(authTag, 0, combined, ciphertext.size, authTag.size)
                                
                                val decryptedBytes = cipher.doFinal(combined)
                                val decryptedStr = String(decryptedBytes, Charsets.UTF_8)
                                val json = org.json.JSONObject(decryptedStr)
                                cfId = json.optString("id").takeIf { it.isNotBlank() } ?: cfId
                                cfSecret = json.optString("secret").takeIf { it.isNotBlank() } ?: cfSecret
                            }
                        } catch (e: Exception) {
                            Log.e("QRScanner", "Failed to decrypt cfenc", e)
                        }
                    }
                    if (url != null && token != null) {
                        onQRScanned(url, token, cfId, cfSecret, pubKey)
                    } else {
                        Log.e("QRScanner", "Missing url or token in QR: $barcodeValue")
                        hasDetected = false
                    }
                } else if (uri.scheme == "http" || uri.scheme == "https") {
                    val host = uri.host ?: "127.0.0.1"
                    val port = if (uri.port > 0) uri.port.toString() else "3883"
                    val secret = uri.getQueryParameter("secret") ?: ""
                    val url = "${uri.scheme}://$host:$port"
                    onQRScanned(url, secret, null, null, null)
                } else {
                    Log.e("QRScanner", "Invalid QR scheme: $barcodeValue")
                    hasDetected = false
                }
            } catch (e: Exception) {
                Log.e("QRScanner", "Invalid QR format: $barcodeValue", e)
                hasDetected = false
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputImage = InputImage.fromFilePath(context, it)
                val options = BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
                val scanner = BarcodeScanning.getClient(options)
                scanner.process(inputImage)
                    .addOnSuccessListener { barcodes ->
                        val rawValue = barcodes.firstOrNull()?.rawValue
                        if (!rawValue.isNullOrBlank()) {
                            handleBarcode(rawValue)
                        } else {
                            Toast.makeText(context, "No valid QR code found in image", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("QRScanner", "Failed to decode QR from image", e)
                        Toast.makeText(context, "Failed to read image", Toast.LENGTH_SHORT).show()
                    }
            } catch (e: Exception) {
                Log.e("QRScanner", "Failed to process image URI", e)
                Toast.makeText(context, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Pairing QR") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (cameraPermissionState.status.isGranted) {
                Box(modifier = Modifier.fillMaxSize()) {
                    CameraPreview(onBarcodeDetected = handleBarcode)
                    
                    // Viewfinder Scan Area Overlay
                    val infiniteTransition = rememberInfiniteTransition(label = "scanner")
                    val scanProgress by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(2200, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "laser"
                    )

                    Box(
                        modifier = Modifier
                            .size(280.dp)
                            .align(Alignment.Center)
                            .border(
                                width = 3.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                shape = RoundedCornerShape(20.dp)
                            )
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            val laserY = size.height * scanProgress
                            drawLine(
                                brush = Brush.horizontalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color(0xFFFF9800),
                                        Color.White,
                                        Color(0xFFFF9800),
                                        Color.Transparent
                                    )
                                ),
                                start = Offset(0f, laserY),
                                end = Offset(size.width, laserY),
                                strokeWidth = 6f
                            )
                        }
                    }

                    Surface(
                        color = Color.Black.copy(alpha = 0.75f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 96.dp)
                    ) {
                        Text(
                            text = "Point camera at the Web Dashboard QR Code",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                    }

                    FloatingActionButton(
                        onClick = { galleryLauncher.launch("image/*") },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(24.dp)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = "Pick QR from Gallery")
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Camera permission is required to scan QR codes.")
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                        Text("Grant Permission")
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text("Or you can scan from an image:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { galleryLauncher.launch("image/*") }) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Pick from Gallery")
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPreview(onBarcodeDetected: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val mainExecutor = ContextCompat.getMainExecutor(ctx)
                imageAnalysis.setAnalyzer(
                    Executors.newSingleThreadExecutor(),
                    QRCodeAnalyzer(mainExecutor) { barcodeValue ->
                        onBarcodeDetected(barcodeValue)
                    }
                )

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (exc: Exception) {
                    Log.e("CameraPreview", "Use case binding failed", exc)
                }

            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

class QRCodeAnalyzer(
    private val mainExecutor: Executor,
    private val onBarcodeDetected: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
    private val scanner = BarcodeScanning.getClient(options)
    private var isDetected = false

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (isDetected) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        val rawValue = barcode.rawValue
                        if (!rawValue.isNullOrBlank() && !isDetected) {
                            isDetected = true
                            mainExecutor.execute {
                                onBarcodeDetected(rawValue)
                            }
                            break
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("QRCodeAnalyzer", "Barcode scan failure", e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }
}
