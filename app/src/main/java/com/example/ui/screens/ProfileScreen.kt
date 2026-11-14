package com.example.ui.screens

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.CaptainEntity
import com.example.data.storage.ImageUploadState
import com.example.ui.components.StorageAsyncImage
import com.example.ui.components.UploadStatusBanner
import com.example.ui.components.rememberImageSourceLauncher
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ExpressRed
import com.example.ui.theme.HeroYellow
import com.example.ui.theme.StatusOnlineGreen

@Composable
fun ProfileScreen(
    captain: CaptainEntity?,
    profileUploadState: ImageUploadState = ImageUploadState.Idle,
    idFrontUploadState: ImageUploadState = ImageUploadState.Idle,
    idBackUploadState: ImageUploadState = ImageUploadState.Idle,
    onUploadProfilePhoto: (Uri, onCompleted: () -> Unit) -> Unit = { _, _ -> },
    onUploadIdFront: (Uri, onCompleted: () -> Unit) -> Unit = { _, _ -> },
    onUploadIdBack: (Uri, onCompleted: () -> Unit) -> Unit = { _, _ -> },
    onResetProfileUploadState: () -> Unit = {},
    onResetIdFrontUploadState: () -> Unit = {},
    onResetIdBackUploadState: () -> Unit = {},
    onSaveProfile: (fullName: String, phone: String, email: String, vehicleType: String, licensePlate: String, avatarUri: String) -> Unit,
    onLogout: () -> Unit
) {
    var fullName by remember(captain?.uid, captain?.fullName) { mutableStateOf(captain?.fullName ?: "") }
    var phone by remember(captain?.uid, captain?.phoneNumber) { mutableStateOf(captain?.phoneNumber ?: "") }
    var email by remember(captain?.uid, captain?.email) { mutableStateOf(captain?.email ?: "") }
    var vehicleType by remember(captain?.uid, captain?.vehicleType) {
        mutableStateOf(captain?.vehicleType ?: "سكوتر / دراجة نارية")
    }
    var licensePlate by remember(captain?.uid, captain?.licensePlate) {
        mutableStateOf(captain?.licensePlate ?: "أ ب ج 1 2 3")
    }

    // Preview-before-save local selections (never marked saved until Firebase Storage confirms!)
    var pendingProfilePreviewUri by remember { mutableStateOf<Uri?>(null) }
    var pendingIdFrontPreviewUri by remember { mutableStateOf<Uri?>(null) }
    var pendingIdBackPreviewUri by remember { mutableStateOf<Uri?>(null) }

    val savedProfilePath = captain?.effectiveProfileImage.orEmpty()
    val savedIdFrontPath = captain?.effectiveIdFrontImage.orEmpty()
    val savedIdBackPath = captain?.effectiveIdBackImage.orEmpty()
    val isNationalIdComplete = captain?.isNationalIdComplete == true

    val profileLauncher = rememberImageSourceLauncher(
        filePrefix = "profile_photo",
        dialogTitle = "الصورة الشخصية للكابتن"
    ) { uri ->
        onResetProfileUploadState()
        pendingProfilePreviewUri = uri
    }

    val idFrontLauncher = rememberImageSourceLauncher(
        filePrefix = "id_front",
        dialogTitle = "البطاقة الشخصية - الوجه الأمامي"
    ) { uri ->
        onResetIdFrontUploadState()
        pendingIdFrontPreviewUri = uri
    }

    val idBackLauncher = rememberImageSourceLauncher(
        filePrefix = "id_back",
        dialogTitle = "البطاقة الشخصية - الوجه الخلفي"
    ) { uri ->
        onResetIdBackUploadState()
        pendingIdBackPreviewUri = uri
    }

    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "حسابي - الملف الشخصي",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )

                OutlinedButton(
                    onClick = onLogout,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ExpressRed),
                    border = BorderStroke(1.dp, ExpressRed),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("top_logout_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ExitToApp,
                        contentDescription = "خروج",
                        tint = ExpressRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "تسجيل الخروج",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = ExpressRed
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1. Captain Personal Photo Card (Preview before Save + Camera/Gallery + Firebase Storage)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(112.dp)
                            .clip(CircleShape)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(ExpressRed, Color(0xFF330800))
                                )
                            )
                            .border(
                                width = 3.dp,
                                color = if (pendingProfilePreviewUri != null) StatusOnlineGreen else HeroYellow,
                                shape = CircleShape
                            )
                            .clickable(enabled = !profileUploadState.isBusy) {
                                profileLauncher.openSourceDialog()
                            }
                            .testTag("profile_photo_avatar_box"),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            pendingProfilePreviewUri != null -> {
                                AsyncImage(
                                    model = pendingProfilePreviewUri,
                                    contentDescription = "معاينة الصورة الشخصية قبل الحفظ",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                )
                            }
                            savedProfilePath.isNotBlank() -> {
                                StorageAsyncImage(
                                    pathOrUrl = savedProfilePath,
                                    contentDescription = "الصورة الشخصية للكابتن",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                )
                            }
                            else -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Icon(
                                        imageVector = Icons.Default.AddAPhoto,
                                        contentDescription = null,
                                        tint = HeroYellow,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(HeroYellow)
                                .border(2.dp, DarkSurface, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = "تغيير الصورة الشخصية",
                                tint = Color.Black,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (pendingProfilePreviewUri != null) {
                        Text(
                            text = "معاينة الصورة الشخصية الجديدة قبل الحفظ",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = HeroYellow
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val target = pendingProfilePreviewUri ?: return@Button
                                    onUploadProfilePhoto(target) {
                                        pendingProfilePreviewUri = null
                                    }
                                },
                                enabled = !profileUploadState.isBusy,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = StatusOnlineGreen,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1.4f)
                                    .height(48.dp)
                                    .testTag("confirm_upload_profile_photo_button")
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("حفظ ورفع الصورة", fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    pendingProfilePreviewUri = null
                                    onResetProfileUploadState()
                                },
                                enabled = !profileUploadState.isBusy,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("cancel_profile_preview_button")
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("إلغاء", color = Color.LightGray)
                            }
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { profileLauncher.captureFromCamera() },
                                enabled = !profileUploadState.isBusy,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("profile_camera_button")
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = "كاميرا", tint = HeroYellow, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("الكاميرا", color = Color.White, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = { profileLauncher.pickFromGallery() },
                                enabled = !profileUploadState.isBusy,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("profile_gallery_button")
                            ) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = "المعرض", tint = HeroYellow, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (savedProfilePath.isBlank()) "اختيار من المعرض" else "تغيير الصورة",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (profileUploadState !is ImageUploadState.Idle) {
                        Spacer(modifier = Modifier.height(10.dp))
                        UploadStatusBanner(
                            state = profileUploadState,
                            onRetry = pendingProfilePreviewUri?.let { retryUri ->
                                {
                                    onUploadProfilePhoto(retryUri) {
                                        pendingProfilePreviewUri = null
                                    }
                                }
                            },
                            tagPrefix = "profile_upload"
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = captain?.fullName ?: "الكابتن",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = HeroYellow,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "تقييم الكابتن: ${captain?.rating ?: 4.9} ★",
                            style = MaterialTheme.typography.bodyMedium,
                            color = HeroYellow
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 2 & 3. Private National ID Documents Section (Front + Back independent slots)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Badge,
                                contentDescription = null,
                                tint = HeroYellow,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "صور البطاقة الشخصية (مستندات خاصة)",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = HeroYellow
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "محمية وخاصة",
                            tint = StatusOnlineGreen,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "بيانات سرية محمية في Firebase Storage لا تظهر للعامة ولا لأي كابتن آخر.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Completion verification banner (ONLY complete when BOTH front and back succeeded uploading!)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("national_id_completion_banner"),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isNationalIdComplete) {
                                StatusOnlineGreen.copy(alpha = 0.16f)
                            } else {
                                HeroYellow.copy(alpha = 0.14f)
                            }
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isNationalIdComplete) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (isNationalIdComplete) StatusOnlineGreen else HeroYellow,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isNationalIdComplete) {
                                    "مستند البطاقة الشخصية مكتمل: تم رفع الوجه الأمامي والخلفي بنجاح ✓"
                                } else {
                                    "المستند غير مكتمل بعد: يجب رفع وحفظ الوجه الأمامي والوجه الخلفي معاً"
                                },
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = if (isNationalIdComplete) StatusOnlineGreen else HeroYellow
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Slot A: National ID Front ("captains/{captainId}/documents/id_front.jpg")
                    NationalIdSideCard(
                        title = "البطاقة الشخصية – الوجه الأمامي",
                        subtitle = "صورة واضحة للوجه الأمامي (الاسم والرقم القومي)",
                        savedStoragePath = savedIdFrontPath,
                        pendingPreviewUri = pendingIdFrontPreviewUri,
                        uploadState = idFrontUploadState,
                        tagPrefix = "id_front",
                        onPickCamera = { idFrontLauncher.captureFromCamera() },
                        onPickGallery = { idFrontLauncher.pickFromGallery() },
                        onConfirmUpload = {
                            val target = pendingIdFrontPreviewUri ?: return@NationalIdSideCard
                            onUploadIdFront(target) {
                                pendingIdFrontPreviewUri = null
                            }
                        },
                        onCancelPreview = {
                            pendingIdFrontPreviewUri = null
                            onResetIdFrontUploadState()
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Slot B: National ID Back ("captains/{captainId}/documents/id_back.jpg")
                    NationalIdSideCard(
                        title = "البطاقة الشخصية – الوجه الخلفي",
                        subtitle = "صورة واضحة للوجه الخلفي (تاريخ الإصدار والسريان)",
                        savedStoragePath = savedIdBackPath,
                        pendingPreviewUri = pendingIdBackPreviewUri,
                        uploadState = idBackUploadState,
                        tagPrefix = "id_back",
                        onPickCamera = { idBackLauncher.captureFromCamera() },
                        onPickGallery = { idBackLauncher.pickFromGallery() },
                        onConfirmUpload = {
                            val target = pendingIdBackPreviewUri ?: return@NationalIdSideCard
                            onUploadIdBack(target) {
                                pendingIdBackPreviewUri = null
                            }
                        },
                        onCancelPreview = {
                            pendingIdBackPreviewUri = null
                            onResetIdBackUploadState()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Editable Profile Fields Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Text(
                        text = "بيانات الحساب والمركبة",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = HeroYellow
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text("اسم الكابتن الثلاثي", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = fullName,
                        onValueChange = { fullName = it },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = HeroYellow) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ExpressRed,
                            unfocusedBorderColor = DarkSurfaceVariant,
                            focusedContainerColor = DarkSurfaceVariant,
                            unfocusedContainerColor = DarkSurfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("رقم الهاتف (يستخدم لتسجيل الدخول)", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null, tint = HeroYellow) },
                        placeholder = { Text("مثال: 01012345678", color = Color.Gray) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ExpressRed,
                            unfocusedBorderColor = DarkSurfaceVariant,
                            focusedContainerColor = DarkSurfaceVariant,
                            unfocusedContainerColor = DarkSurfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("البريد الإلكتروني (اختياري)", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = HeroYellow) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ExpressRed,
                            unfocusedBorderColor = DarkSurfaceVariant,
                            focusedContainerColor = DarkSurfaceVariant,
                            unfocusedContainerColor = DarkSurfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("نوع المركبة", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = vehicleType,
                        onValueChange = { vehicleType = it },
                        leadingIcon = { Icon(Icons.Default.TwoWheeler, contentDescription = null, tint = HeroYellow) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ExpressRed,
                            unfocusedBorderColor = DarkSurfaceVariant,
                            focusedContainerColor = DarkSurfaceVariant,
                            unfocusedContainerColor = DarkSurfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("رقم اللوحة المعدنية", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = licensePlate,
                        onValueChange = { licensePlate = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ExpressRed,
                            unfocusedBorderColor = DarkSurfaceVariant,
                            focusedContainerColor = DarkSurfaceVariant,
                            unfocusedContainerColor = DarkSurfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            onSaveProfile(
                                fullName,
                                phone,
                                email,
                                vehicleType,
                                licensePlate,
                                savedProfilePath
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ExpressRed),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("save_profile_info_button")
                    ) {
                        Icon(imageVector = Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "حفظ التعديلات",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = onLogout,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ExpressRed),
                border = BorderStroke(1.5.dp, ExpressRed),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("bottom_logout_button")
            ) {
                Icon(
                    imageVector = Icons.Default.ExitToApp,
                    contentDescription = "تسجيل الخروج",
                    tint = ExpressRed,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "تسجيل الخروج من حساب الكابتن",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = ExpressRed
                )
            }

            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@Composable
private fun NationalIdSideCard(
    title: String,
    subtitle: String,
    savedStoragePath: String,
    pendingPreviewUri: Uri?,
    uploadState: ImageUploadState,
    tagPrefix: String,
    onPickCamera: () -> Unit,
    onPickGallery: () -> Unit,
    onConfirmUpload: () -> Unit,
    onCancelPreview: () -> Unit
) {
    val isUploadedAndSaved = savedStoragePath.isNotBlank() && pendingPreviewUri == null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = when {
                    pendingPreviewUri != null -> HeroYellow
                    isUploadedAndSaved -> StatusOnlineGreen.copy(alpha = 0.6f)
                    else -> DarkSurfaceVariant
                },
                shape = RoundedCornerShape(16.dp)
            )
            .testTag("${tagPrefix}_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        text = when {
                            pendingPreviewUri != null -> "معاينة قبل الحفظ — اضغط حفظ ورفع لتأكيد المستند"
                            isUploadedAndSaved -> "تم الرفع والحفظ في Firebase Storage ✓"
                            else -> subtitle
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            pendingPreviewUri != null -> HeroYellow
                            isUploadedAndSaved -> StatusOnlineGreen
                            else -> Color.LightGray
                        }
                    )
                }

                Icon(
                    imageVector = if (isUploadedAndSaved) Icons.Default.CheckCircle else Icons.Default.Badge,
                    contentDescription = null,
                    tint = if (isUploadedAndSaved) StatusOnlineGreen else HeroYellow,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Image preview / saved image frame
            if (pendingPreviewUri != null || savedStoragePath.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(165.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurface),
                    contentAlignment = Alignment.Center
                ) {
                    if (pendingPreviewUri != null) {
                        AsyncImage(
                            model = pendingPreviewUri,
                            contentDescription = "معاينة $title",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        StorageAsyncImage(
                            pathOrUrl = savedStoragePath,
                            contentDescription = title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (pendingPreviewUri != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onConfirmUpload,
                        enabled = !uploadState.isBusy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StatusOnlineGreen,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1.4f)
                            .height(48.dp)
                            .testTag("${tagPrefix}_confirm_upload_button")
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("حفظ ورفع المستند", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = onCancelPreview,
                        enabled = !uploadState.isBusy,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("${tagPrefix}_cancel_preview_button")
                    ) {
                        Text("إلغاء", color = Color.LightGray)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onPickCamera,
                        enabled = !uploadState.isBusy,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("${tagPrefix}_camera_button")
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "تصوير", tint = HeroYellow, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (savedStoragePath.isBlank()) "تصوير" else "إعادة تصوير",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onPickGallery,
                        enabled = !uploadState.isBusy,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("${tagPrefix}_gallery_button")
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = "المعرض", tint = HeroYellow, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (savedStoragePath.isBlank()) "من المعرض" else "استبدال",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (uploadState !is ImageUploadState.Idle) {
                Spacer(modifier = Modifier.height(10.dp))
                UploadStatusBanner(
                    state = uploadState,
                    onRetry = if (pendingPreviewUri != null) onConfirmUpload else null,
                    tagPrefix = tagPrefix
                )
            }
        }
    }
}
