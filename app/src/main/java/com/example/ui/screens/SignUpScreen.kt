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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DriveEta
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.ui.components.rememberImageSourceLauncher
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ExpressRed
import com.example.ui.theme.HeroYellow
import com.example.ui.theme.StatusOnlineGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignUpScreen(
    isLoading: Boolean = false,
    onSignUpClick: (
        fullName: String,
        phone: String,
        email: String,
        pass: String,
        confirmPass: String,
        avatarUri: String,
        avatarPresetIndex: Int,
        vehicleType: String,
        nationalIdUri: String,
        idBackUri: String,
        licenseUri: String,
        onError: (String) -> Unit
    ) -> Unit,
    onNavigateLogin: () -> Unit
) {
    var fullName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var isConfirmPasswordVisible by remember { mutableStateOf(false) }

    var avatarUri by remember { mutableStateOf("") }
    var nationalIdFrontUri by remember { mutableStateOf("") }
    var nationalIdBackUri by remember { mutableStateOf("") }
    var licenseUri by remember { mutableStateOf("") }
    val selectedAvatarIndex by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val profileSourceLauncher = rememberImageSourceLauncher(
        filePrefix = "signup_profile",
        dialogTitle = "الصورة الشخصية للكابتن"
    ) { uri: Uri ->
        avatarUri = uri.toString()
    }

    val idFrontSourceLauncher = rememberImageSourceLauncher(
        filePrefix = "signup_id_front",
        dialogTitle = "البطاقة الشخصية – الوجه الأمامي"
    ) { uri: Uri ->
        nationalIdFrontUri = uri.toString()
        errorMessage = null
    }

    val idBackSourceLauncher = rememberImageSourceLauncher(
        filePrefix = "signup_id_back",
        dialogTitle = "البطاقة الشخصية – الوجه الخلفي"
    ) { uri: Uri ->
        nationalIdBackUri = uri.toString()
        errorMessage = null
    }

    val licenseSourceLauncher = rememberImageSourceLauncher(
        filePrefix = "signup_license",
        dialogTitle = "صورة رخصة القيادة / المركبة"
    ) { uri: Uri ->
        licenseUri = uri.toString()
    }

    val vehicleTypes = listOf("سكوتر / دراجة نارية", "دراجة هوائية", "سيارة توصيل", "شاحنة خفيفة")
    var vehicleDropdownExpanded by remember { mutableStateOf(false) }
    var selectedVehicle by remember { mutableStateOf(vehicleTypes[0]) }

    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateLogin) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "الرجوع",
                        tint = ExpressRed
                    )
                }
                Text(
                    text = "إنشاء حساب كابتن جديد",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Text(
                text = "انضم إلى كباتن أسطول البطل إكسبريس وابدأ باستقبال الطلبات",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, bottom = 20.dp)
            )

            // 1. Profile Picture Card (Camera or Gallery + Preview)
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
                    Text(
                        text = "الصورة الشخصية للكابتن *",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = HeroYellow
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Box(
                        modifier = Modifier
                            .size(105.dp)
                            .clip(CircleShape)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(ExpressRed, Color(0xFF380800))
                                )
                            )
                            .border(3.dp, if (avatarUri.isNotBlank()) HeroYellow else ExpressRed, CircleShape)
                            .clickable(enabled = !isLoading) {
                                profileSourceLauncher.openSourceDialog()
                            }
                            .testTag("signup_profile_photo_box"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (avatarUri.isNotBlank()) {
                            AsyncImage(
                                model = avatarUri,
                                contentDescription = "معاينة الصورة الشخصية للكابتن",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                            )
                        } else {
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
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(HeroYellow)
                                .border(2.dp, DarkSurface, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = "اختيار أو التقاط صورة",
                                tint = Color.Black,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = if (avatarUri.isNotBlank()) {
                            "✓ معاينة الصورة الشخصية جاهزة للرفع إلى Firebase Storage (اضغط للتغيير)"
                        } else {
                            "اضغط لالتقاط صورتك بالكاميرا أو اختيارها من المعرض 📷"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = if (avatarUri.isNotBlank()) HeroYellow else Color.LightGray
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Form Fields & Documents Card
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
                        text = "الاسم الكامل *",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = fullName,
                        onValueChange = {
                            fullName = it
                            errorMessage = null
                        },
                        placeholder = { Text("مثال: أحمد محمد علي", color = Color.Gray) },
                        leadingIcon = {
                            Icon(Icons.Default.Person, contentDescription = null, tint = HeroYellow)
                        },
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

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "رقم الهاتف *",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = phone,
                        onValueChange = {
                            phone = it
                            errorMessage = null
                        },
                        placeholder = { Text("مثال: 01099887766", color = Color.Gray) },
                        leadingIcon = {
                            Icon(Icons.Default.Phone, contentDescription = null, tint = HeroYellow)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ExpressRed,
                            unfocusedBorderColor = DarkSurfaceVariant,
                            focusedContainerColor = DarkSurfaceVariant,
                            unfocusedContainerColor = DarkSurfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "البريد الإلكتروني *",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "(مطلوب لتأكيد وتفعيل الحساب)",
                            style = MaterialTheme.typography.labelSmall,
                            color = HeroYellow
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = email,
                        onValueChange = {
                            email = it
                            errorMessage = null
                        },
                        placeholder = { Text("مثال: captain@example.com", color = Color.Gray) },
                        leadingIcon = {
                            Icon(Icons.Default.Email, contentDescription = null, tint = HeroYellow)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ExpressRed,
                            unfocusedBorderColor = DarkSurfaceVariant,
                            focusedContainerColor = DarkSurfaceVariant,
                            unfocusedContainerColor = DarkSurfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "كلمة المرور *",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "(6 خانات على الأقل)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            errorMessage = null
                        },
                        placeholder = { Text("أدخل 6 أحرف أو أرقام على الأقل", color = Color.Gray) },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = HeroYellow)
                        },
                        trailingIcon = {
                            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                Icon(
                                    imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = "إظهار أو إخفاء كلمة المرور",
                                    tint = Color.Gray
                                )
                            }
                        },
                        singleLine = true,
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ExpressRed,
                            unfocusedBorderColor = DarkSurfaceVariant,
                            focusedContainerColor = DarkSurfaceVariant,
                            unfocusedContainerColor = DarkSurfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "تأكيد كلمة المرور *",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = {
                            confirmPassword = it
                            errorMessage = null
                        },
                        placeholder = { Text("أعد إدخال كلمة المرور للتأكيد", color = Color.Gray) },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = HeroYellow)
                        },
                        trailingIcon = {
                            IconButton(onClick = { isConfirmPasswordVisible = !isConfirmPasswordVisible }) {
                                Icon(
                                    imageVector = if (isConfirmPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = "إظهار أو إخفاء التأكيد",
                                    tint = Color.Gray
                                )
                            }
                        },
                        singleLine = true,
                        visualTransformation = if (isConfirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ExpressRed,
                            unfocusedBorderColor = DarkSurfaceVariant,
                            focusedContainerColor = DarkSurfaceVariant,
                            unfocusedContainerColor = DarkSurfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Text(
                        text = "مستندات البطاقة الشخصية (الوجه الأمامي والخلفي)",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = HeroYellow
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // 2. National ID Front Independent Slot
                    DocumentUploadItem(
                        title = "البطاقة الشخصية – الوجه الأمامي",
                        subtitle = "تصوير بالكاميرا أو اختيار من المعرض (خاص ومحمي)",
                        icon = Icons.Default.Badge,
                        currentUri = nationalIdFrontUri,
                        testTag = "signup_id_front_slot",
                        onPickClick = { idFrontSourceLauncher.openSourceDialog() }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 3. National ID Back Independent Slot
                    DocumentUploadItem(
                        title = "البطاقة الشخصية – الوجه الخلفي",
                        subtitle = "تصوير بالكاميرا أو اختيار من المعرض (خاص ومحمي)",
                        icon = Icons.Default.Badge,
                        currentUri = nationalIdBackUri,
                        testTag = "signup_id_back_slot",
                        onPickClick = { idBackSourceLauncher.openSourceDialog() }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Driving License Slot
                    DocumentUploadItem(
                        title = "صورة رخصة القيادة / المركبة (اختياري)",
                        subtitle = "رخصة قيادة أو رخصة المركبة سارية",
                        icon = Icons.Default.DriveEta,
                        currentUri = licenseUri,
                        testTag = "signup_license_slot",
                        onPickClick = { licenseSourceLauncher.openSourceDialog() }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "نوع مركبة التوصيل",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    ExposedDropdownMenuBox(
                        expanded = vehicleDropdownExpanded,
                        onExpandedChange = { vehicleDropdownExpanded = !vehicleDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedVehicle,
                            onValueChange = {},
                            readOnly = true,
                            leadingIcon = {
                                Icon(Icons.Default.TwoWheeler, contentDescription = null, tint = HeroYellow)
                            },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = vehicleDropdownExpanded) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ExpressRed,
                                unfocusedBorderColor = DarkSurfaceVariant,
                                focusedContainerColor = DarkSurfaceVariant,
                                unfocusedContainerColor = DarkSurfaceVariant
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = vehicleDropdownExpanded,
                            onDismissRequest = { vehicleDropdownExpanded = false },
                            modifier = Modifier.background(DarkSurfaceVariant)
                        ) {
                            vehicleTypes.forEach { vehicle ->
                                DropdownMenuItem(
                                    text = { Text(vehicle, color = Color.White) },
                                    onClick = {
                                        selectedVehicle = vehicle
                                        vehicleDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    errorMessage?.let { err ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = err,
                            color = ExpressRed,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            val trimmedName = fullName.trim()
                            val trimmedPhone = phone.trim()
                            val trimmedEmail = email.trim()

                            if (trimmedName.isBlank()) {
                                errorMessage = "يرجى إدخال اسم الكابتن الكامل"
                                return@Button
                            }
                            if (trimmedPhone.length < 8) {
                                errorMessage = "يرجى إدخال رقم هاتف صحيح"
                                return@Button
                            }
                            if (trimmedEmail.isBlank()) {
                                errorMessage = "البريد الإلكتروني إلزامي لتأكيد الحساب وتفعيله"
                                return@Button
                            }
                            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
                                errorMessage = "صيغة البريد الإلكتروني غير صحيحة (مثال: name@example.com)"
                                return@Button
                            }
                            if (password.length < 6) {
                                errorMessage = "كلمة المرور يجب ألا تقل عن 6 أحرف أو أرقام"
                                return@Button
                            }
                            if (password != confirmPassword) {
                                errorMessage = "كلمة المرور وتأكيد كلمة المرور غير متطابقتين"
                                return@Button
                            }
                            if ((nationalIdFrontUri.isNotBlank() && nationalIdBackUri.isBlank()) ||
                                (nationalIdFrontUri.isBlank() && nationalIdBackUri.isNotBlank())
                            ) {
                                errorMessage = "لا يعتبر مستند البطاقة مكتملًا إلا برفع الوجه الأمامي والخلفي معاً"
                                return@Button
                            }

                            onSignUpClick(
                                trimmedName,
                                trimmedPhone,
                                trimmedEmail,
                                password,
                                confirmPassword,
                                avatarUri,
                                selectedAvatarIndex,
                                selectedVehicle,
                                nationalIdFrontUri,
                                nationalIdBackUri,
                                licenseUri
                            ) { err ->
                                errorMessage = err
                            }
                        },
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ExpressRed,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("signup_submit_button")
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "جاري رفع الصور وإنشاء الحساب...",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.MarkEmailRead,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "إنشاء حساب وإرسال رابط التأكيد",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "لديك حساب بالفعل؟ ",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "تسجيل الدخول",
                    color = HeroYellow,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.clickable { onNavigateLogin() }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DocumentUploadItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    currentUri: String,
    testTag: String,
    onPickClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPickClick() }
            .testTag(testTag),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        border = BorderStroke(
            1.dp,
            if (currentUri.isNotBlank()) StatusOnlineGreen else DarkSurfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (currentUri.isNotBlank()) {
                AsyncImage(
                    model = currentUri,
                    contentDescription = "معاينة $title",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF262A35)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = HeroYellow,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Text(
                    text = if (currentUri.isNotBlank()) "✓ معاينة جاهزة (اضغط للاستبدال)" else subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (currentUri.isNotBlank()) StatusOnlineGreen else Color.Gray
                )
            }

            Icon(
                imageVector = if (currentUri.isNotBlank()) Icons.Default.CheckCircle else Icons.Default.AddAPhoto,
                contentDescription = "رفع",
                tint = if (currentUri.isNotBlank()) StatusOnlineGreen else HeroYellow,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
