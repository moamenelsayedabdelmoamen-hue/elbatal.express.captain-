package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.PhoneAuthRegistry
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ExpressRed
import com.example.ui.theme.HeroYellow

@Composable
fun LoginScreen(
    isLoading: Boolean = false,
    onLoginClick: (
        phone: String,
        pass: String,
        onNeedsEmailVerification: (String) -> Unit,
        onError: (String) -> Unit
    ) -> Unit,
    onNavigateSignUp: () -> Unit,
    onNavigateVerifyEmail: (String) -> Unit = {},
    onForgotPasswordClick: ((String, (String) -> Unit, (String) -> Unit) -> Unit)? = null
) {
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isPhoneMode by remember { mutableStateOf(true) }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showForgotPasswordDialog by remember { mutableStateOf(false) }
    var forgotPhoneInput by remember { mutableStateOf("") }
    var forgotSuccessMsg by remember { mutableStateOf<String?>(null) }
    var unverifiedEmailPrompt by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()

    val logoDrawableId = try {
        com.example.R.drawable.el_batal_logo_1784764128367
    } catch (e: Exception) { 0 }

    val heroBannerDrawableId = try {
        com.example.R.drawable.captain_hero_banner_1784764142373
    } catch (e: Exception) { 0 }

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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // Banner Image & Logo Header
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (heroBannerDrawableId != 0) {
                        Image(
                            painter = painterResource(id = heroBannerDrawableId),
                            contentDescription = "غلاف البطل إكسبريس",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f))
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        if (logoDrawableId != 0) {
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(id = logoDrawableId),
                                    contentDescription = "الشعار",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                        }
                        Column {
                            Text(
                                text = "البطل إكسبريس",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 22.sp
                                ),
                                color = ExpressRed
                            )
                            Text(
                                text = "تطبيق الكابتن",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                ),
                                color = HeroYellow
                            )
                            Text(
                                text = "استقبل طلبات التوصيل فوراً",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Title section
            Text(
                text = "تسجيل دخول الكابتن",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = "أدخل البريد الإلكتروني أو رقم الهاتف وكلمة السر للمتابعة",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
            )

            // Form Fields Card
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
                    // Login Mode Toggle: Phone Number vs Email
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(DarkSurfaceVariant)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isPhoneMode) ExpressRed else Color.Transparent)
                                .clickable {
                                    isPhoneMode = true
                                    errorMessage = null
                                }
                                .testTag("login_mode_phone_tab"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Phone,
                                    contentDescription = null,
                                    tint = if (isPhoneMode) Color.White else Color.Gray,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "برقم الهاتف",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = if (isPhoneMode) Color.White else Color.LightGray
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (!isPhoneMode) ExpressRed else Color.Transparent)
                                .clickable {
                                    isPhoneMode = false
                                    errorMessage = null
                                }
                                .testTag("login_mode_email_tab"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Email,
                                    contentDescription = null,
                                    tint = if (!isPhoneMode) Color.White else Color.Gray,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "بالبريد الإلكتروني",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = if (!isPhoneMode) Color.White else Color.LightGray
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Phone or Email Field
                    Text(
                        text = if (isPhoneMode) "رقم الهاتف المسجل" else "البريد الإلكتروني المسجل",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { raw ->
                            phone = if (isPhoneMode && !raw.contains("@")) {
                                PhoneAuthRegistry.cleanInvisibleChars(raw)
                            } else {
                                raw
                            }
                            if (raw.contains("@") && isPhoneMode) {
                                isPhoneMode = false
                            }
                            errorMessage = null
                        },
                        placeholder = {
                            Text(
                                text = if (isPhoneMode) "مثال: 01012345678" else "captain@example.com",
                                color = Color.Gray
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (!isPhoneMode || phone.contains("@")) Icons.Default.Email else Icons.Default.Phone,
                                contentDescription = null,
                                tint = HeroYellow
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (isPhoneMode && !phone.contains("@")) KeyboardType.Phone else KeyboardType.Email
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ExpressRed,
                            unfocusedBorderColor = DarkSurfaceVariant,
                            focusedContainerColor = DarkSurfaceVariant,
                            unfocusedContainerColor = DarkSurfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("login_phone_input")
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Password Field
                    Text(
                        text = "كلمة السر",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            errorMessage = null
                        },
                        placeholder = { Text("أدخل كلمة السر", color = Color.Gray) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = HeroYellow
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                Icon(
                                    imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = "عرض كلمة السر",
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("login_password_input")
                    )

                    // Error text if any
                    errorMessage?.let { err ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = err,
                            color = ExpressRed,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Forgot Password link
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Text(
                            text = "نسيت كلمة السر؟",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = HeroYellow,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.clickable {
                                forgotPhoneInput = phone
                                forgotSuccessMsg = null
                                showForgotPasswordDialog = true
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Login Button
                    Button(
                        onClick = {
                            onLoginClick(
                                PhoneAuthRegistry.cleanInvisibleChars(phone),
                                password,
                                { unverifiedEmail ->
                                    unverifiedEmailPrompt = unverifiedEmail
                                },
                                { err ->
                                    errorMessage = err
                                }
                            )
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
                            .testTag("login_submit_button")
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "جاري تسجيل الدخول...",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            )
                        } else {
                            Text(
                                text = if (isPhoneMode) "دخول برقم الهاتف" else "تسجيل الدخول",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Navigation to Sign Up
            Row(
                modifier = Modifier.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ليس لديك حساب كابتن؟ ",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "إنشاء حساب جديد",
                    color = HeroYellow,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.clickable { onNavigateSignUp() }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }

        // Forgot Password Dialog
        if (showForgotPasswordDialog) {
            AlertDialog(
                onDismissRequest = { showForgotPasswordDialog = false },
                title = {
                    Text(
                        text = "استعادة كلمة السر",
                        style = MaterialTheme.typography.titleLarge,
                        color = ExpressRed,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "أدخل البريد الإلكتروني أو رقم الهاتف المرتبط بحسابك لاستعادة كلمة السر:",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = forgotPhoneInput,
                            onValueChange = { forgotPhoneInput = it },
                            placeholder = { Text("example@email.com أو 010XXXXXXXX") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.fillMaxWidth()
                        )
                        forgotSuccessMsg?.let { msg ->
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = msg,
                                color = HeroYellow,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (forgotPhoneInput.isNotBlank()) {
                                if (onForgotPasswordClick != null) {
                                    onForgotPasswordClick(
                                        forgotPhoneInput.trim(),
                                        { success -> forgotSuccessMsg = success },
                                        { error -> forgotSuccessMsg = error }
                                    )
                                } else {
                                    forgotSuccessMsg = if (forgotPhoneInput.contains("@")) {
                                        "تم إرسال رابط إعادة تعيين كلمة السر إلى بريدك الإلكتروني بنجاح."
                                    } else {
                                        "تم إرسال رمز استعادة كلمة السر إلى $forgotPhoneInput"
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ExpressRed)
                    ) {
                        Text("إرسال الرابط / الرمز")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showForgotPasswordDialog = false }) {
                        Text("إلغاء", color = Color.Gray)
                    }
                },
                containerColor = DarkSurface
            )
        }

        // Unverified Email Dialog
        unverifiedEmailPrompt?.let { unverifiedEmail ->
            AlertDialog(
                onDismissRequest = { unverifiedEmailPrompt = null },
                icon = {
                    Icon(
                        imageVector = Icons.Default.MarkEmailRead,
                        contentDescription = null,
                        tint = HeroYellow,
                        modifier = Modifier.size(36.dp)
                    )
                },
                title = {
                    Text(
                        text = "تأكيد البريد الإلكتروني مطلوب",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "حسابك ككابتن لم يتم تفعيله بعد، حيث يلزم تأكيد البريد الإلكتروني أولاً لتتمكن من استقبال الطلبات.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.LightGray
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "البريد المسجل: $unverifiedEmail",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = HeroYellow
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val emailToPass = unverifiedEmailPrompt ?: ""
                            unverifiedEmailPrompt = null
                            onNavigateVerifyEmail(emailToPass)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ExpressRed,
                            contentColor = Color.White
                        )
                    ) {
                        Text("تأكيد الحساب الآن")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { unverifiedEmailPrompt = null }) {
                        Text("إلغاء", color = Color.Gray)
                    }
                },
                containerColor = DarkSurface,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}
