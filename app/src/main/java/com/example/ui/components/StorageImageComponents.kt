package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.data.storage.FirebaseStorageRepository
import com.example.data.storage.ImageUploadState
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ExpressRed
import com.example.ui.theme.HeroYellow
import com.example.ui.theme.StatusOnlineGreen

val LocalStorageRepository = compositionLocalOf<FirebaseStorageRepository?> { null }

/**
 * Renders both:
 * 1. Authenticated private Firebase Storage paths (`captains/{uid}/...`, `orders/{id}/receipts/...`)
 *    without exposing open public links.
 * 2. Legacy remote URLs (`https://...` such as historical ImgBB images in Firestore) and local preview `Uri`s.
 */
@Composable
fun StorageAsyncImage(
    pathOrUrl: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val repo = LocalStorageRepository.current ?: remember(context) {
        FirebaseStorageRepository.getInstance(context)
    }

    val trimmed = pathOrUrl.trim()
    if (trimmed.isBlank()) {
        fallback?.invoke()
        return
    }

    if (FirebaseStorageRepository.isLegacyOrLocalDirectUri(trimmed)) {
        AsyncImage(
            model = trimmed,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier
        )
        return
    }

    val revision = repo.getPathRevision(trimmed)
    var resolvedModel by remember(trimmed, revision) { mutableStateOf<Any?>(null) }
    var isLoading by remember(trimmed, revision) { mutableStateOf(true) }
    var loadError by remember(trimmed, revision) { mutableStateOf(false) }

    LaunchedEffect(trimmed, revision) {
        isLoading = true
        loadError = false
        val result = repo.resolveDisplayModel(trimmed)
        if (result.isSuccess) {
            resolvedModel = result.getOrNull()
            isLoading = false
        } else {
            loadError = true
            isLoading = false
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            resolvedModel != null -> {
                AsyncImage(
                    model = resolvedModel,
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize()
                )
            }
            isLoading -> {
                CircularProgressIndicator(
                    color = HeroYellow,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp)
                )
            }
            loadError -> {
                if (fallback != null) {
                    fallback()
                } else {
                    Icon(
                        imageVector = Icons.Default.BrokenImage,
                        contentDescription = contentDescription,
                        tint = Color.Gray,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

class ImageSourceLauncherState(
    val openSourceDialog: () -> Unit,
    val pickFromGallery: () -> Unit,
    val captureFromCamera: () -> Unit
)

/**
 * Reusable Camera + Zero-Permission Photo Picker (`PickVisualMedia`) state holder.
 * Strictly adheres to Google Play Photo Picker policy (no READ_EXTERNAL_STORAGE / READ_MEDIA_IMAGES).
 */
@Composable
fun rememberImageSourceLauncher(
    filePrefix: String = "captain_img",
    dialogTitle: String = "اختر مصدر الصورة",
    onImageSelected: (Uri) -> Unit
): ImageSourceLauncherState {
    val context = LocalContext.current
    val repo = LocalStorageRepository.current ?: remember(context) {
        FirebaseStorageRepository.getInstance(context)
    }

    var showChooserDialog by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            onImageSelected(uri)
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val captured = pendingCameraUri
        if (success && captured != null) {
            onImageSelected(captured)
        }
    }

    val launchCameraNow = {
        try {
            val uri = repo.createCameraCaptureUri(context, filePrefix)
            pendingCameraUri = uri
            takePictureLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "تعذر فتح الكاميرا: ${e.localizedMessage ?: "يرجى المحاولة من المعرض"}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchCameraNow()
        } else {
            Toast.makeText(
                context,
                "يلزم منح إذن الكاميرا لالتقاط الصورة، أو يمكنك الاختيار من المعرض",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val triggerCamera = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchCameraNow()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val triggerGallery = {
        galleryLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    if (showChooserDialog) {
        AlertDialog(
            onDismissRequest = { showChooserDialog = false },
            containerColor = DarkSurface,
            title = {
                Text(
                    text = dialogTitle,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            showChooserDialog = false
                            triggerCamera()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ExpressRed,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("${filePrefix}_camera_option")
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "الكاميرا")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("التقاط صورة بالكاميرا", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            showChooserDialog = false
                            triggerGallery()
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("${filePrefix}_gallery_option")
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = "المعرض", tint = HeroYellow)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("اختيار صورة من المعرض", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showChooserDialog = false }) {
                    Text("إلغاء", color = Color.LightGray)
                }
            }
        )
    }

    return remember(filePrefix) {
        ImageSourceLauncherState(
            openSourceDialog = { showChooserDialog = true },
            pickFromGallery = triggerGallery,
            captureFromCamera = triggerCamera
        )
    }
}

/**
 * Displays real-time upload progress percentage, state text, failure diagnostics, and a Retry button.
 */
@Composable
fun UploadStatusBanner(
    state: ImageUploadState,
    onRetry: (() -> Unit)? = null,
    tagPrefix: String = "upload"
) {
    when (state) {
        is ImageUploadState.Idle -> Unit
        is ImageUploadState.Validating -> {
            UploadProgressCard(
                label = "جاري التحقق من نوع وحجم الصورة...",
                progressPercent = null,
                tagPrefix = tagPrefix
            )
        }
        is ImageUploadState.Compressing -> {
            UploadProgressCard(
                label = "جاري ضغط وتحسين الصورة قبل الرفع...",
                progressPercent = null,
                tagPrefix = tagPrefix
            )
        }
        is ImageUploadState.Uploading -> {
            UploadProgressCard(
                label = "جاري الرفع إلى Firebase Storage (${state.progressPercent}%)",
                progressPercent = state.progressPercent,
                tagPrefix = tagPrefix
            )
        }
        is ImageUploadState.Retrying -> {
            UploadProgressCard(
                label = "إعادة المحاولة (${state.attempt}/${state.maxAttempts}): ${state.reason}",
                progressPercent = null,
                tagPrefix = tagPrefix,
                accentColor = HeroYellow
            )
        }
        is ImageUploadState.Success -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("${tagPrefix}_success_banner"),
                colors = CardDefaults.cardColors(containerColor = StatusOnlineGreen.copy(alpha = 0.16f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = StatusOnlineGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "تم رفع وحفظ الصورة بنجاح في Firebase Storage ✓",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = StatusOnlineGreen
                    )
                }
            }
        }
        is ImageUploadState.Failed -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("${tagPrefix}_error_banner"),
                colors = CardDefaults.cardColors(containerColor = ExpressRed.copy(alpha = 0.18f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = ExpressRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = state.errorMessage,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = ExpressRed
                        )
                    }
                    if (state.isRetryable && onRetry != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onRetry,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("${tagPrefix}_retry_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "إعادة المحاولة",
                                tint = HeroYellow,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "إعادة المحاولة",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadProgressCard(
    label: String,
    progressPercent: Int?,
    tagPrefix: String,
    accentColor: Color = HeroYellow
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("${tagPrefix}_progress_banner"),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                if (progressPercent != null) {
                    Text(
                        text = "$progressPercent%",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black),
                        color = accentColor
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (progressPercent != null) {
                LinearProgressIndicator(
                    progress = { (progressPercent / 100f).coerceIn(0f, 1f) },
                    color = accentColor,
                    trackColor = DarkSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
            } else {
                LinearProgressIndicator(
                    color = accentColor,
                    trackColor = DarkSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
            }
        }
    }
}
