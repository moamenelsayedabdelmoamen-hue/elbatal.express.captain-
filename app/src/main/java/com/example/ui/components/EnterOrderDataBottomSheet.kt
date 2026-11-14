package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.OrderEntity
import com.example.data.storage.ImageUploadState
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ExpressRed
import com.example.ui.theme.HeroYellow
import com.example.ui.theme.StatusOnlineGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnterOrderDataBottomSheet(
    order: OrderEntity,
    sheetState: SheetState,
    isUploading: Boolean = false,
    receiptUploadState: ImageUploadState = ImageUploadState.Idle,
    onDismiss: () -> Unit,
    onSaveDataAndImage: (customerName: String, customerPhone: String, notes: String, imageUri: Uri) -> Unit
) {
    val context = LocalContext.current
    var customerNameInput by remember(order.id) { mutableStateOf(order.customerName) }
    var customerPhoneInput by remember(order.id) { mutableStateOf(order.customerPhone) }
    var notesInput by remember(order.id) { mutableStateOf(order.notes) }
    var selectedLocalReceiptUri by remember(order.id) { mutableStateOf<Uri?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val existingSavedReceipt = order.effectiveReceiptImage
    val isBusy = isUploading || receiptUploadState.isBusy

    val receiptSourceLauncher = rememberImageSourceLauncher(
        filePrefix = "order_receipt_${order.id}",
        dialogTitle = "صورة إيصال الطلب #${order.cleanOrderNumber}"
    ) { uri: Uri ->
        selectedLocalReceiptUri = uri
        validationError = null
    }

    val submitAction = {
        val nameTrimmed = customerNameInput.trim()
        val phoneTrimmed = customerPhoneInput.trim()

        when {
            nameTrimmed.length < 2 -> {
                validationError = "يرجى كتابة اسم العميل بشكل صحيح"
            }
            phoneTrimmed.length < 9 -> {
                validationError = "يرجى إدخال رقم هاتف صحيح للعميل"
            }
            selectedLocalReceiptUri == null -> {
                validationError = "يجب التقاط صورة الإيصال بالكاميرا أو اختيارها من المعرض"
            }
            else -> {
                validationError = null
                onSaveDataAndImage(nameTrimmed, phoneTrimmed, notesInput.trim(), selectedLocalReceiptUri!!)
            }
        }
    }

    val scrollState = rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = { if (!isBusy) onDismiss() },
        sheetState = sheetState,
        containerColor = DarkBackground,
        scrimColor = Color.Black.copy(alpha = 0.65f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "تسجيل بيانات العميل والإيصال",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        ),
                        color = Color.White
                    )
                    Text(
                        text = "طلب رقم: #${order.cleanOrderNumber} • ${order.storeName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = HeroYellow
                    )
                }

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(HeroYellow.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ReceiptLong,
                        contentDescription = null,
                        tint = HeroYellow,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 1. Customer Name
            Text(
                text = "اسم العميل المستلم *",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = HeroYellow
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = customerNameInput,
                onValueChange = {
                    customerNameInput = it
                    validationError = null
                },
                placeholder = { Text("أدخل اسم العميل", color = Color.Gray) },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Person, contentDescription = null, tint = HeroYellow)
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ExpressRed,
                    unfocusedBorderColor = DarkSurfaceVariant,
                    focusedContainerColor = DarkSurface,
                    unfocusedContainerColor = DarkSurface,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("receipt_customer_name_input")
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Customer Phone
            Text(
                text = "رقم هاتف العميل للتواصل *",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = HeroYellow
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = customerPhoneInput,
                onValueChange = {
                    customerPhoneInput = it
                    validationError = null
                },
                placeholder = { Text("مثال: 01012345678", color = Color.Gray) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Phone, contentDescription = null, tint = StatusOnlineGreen)
                },
                trailingIcon = {
                    if (customerPhoneInput.isNotBlank()) {
                        IconButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$customerPhoneInput"))
                                context.startActivity(intent)
                            }
                        ) {
                            Icon(Icons.Default.Call, contentDescription = "اتصال بالعميل", tint = StatusOnlineGreen)
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ExpressRed,
                    unfocusedBorderColor = DarkSurfaceVariant,
                    focusedContainerColor = DarkSurface,
                    unfocusedContainerColor = DarkSurface,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("receipt_customer_phone_input")
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "منطقة وعنوان التسليم:",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
            Text(
                text = "📍 ${order.deliveryAddress}",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = Color.White
            )

            Spacer(modifier = Modifier.height(18.dp))

            // 3. Receipt Image Section (Camera or Gallery + Preview)
            Text(
                text = "صورة إيصال الطلب / الفاتورة *",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = HeroYellow
            )
            Text(
                text = "التقط صورة الإيصال بالكاميرا أو اخترها من المعرض ليتم رفعها بأمان إلى Firebase Storage",
                style = MaterialTheme.typography.bodySmall,
                color = Color.LightGray
            )

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurface)
                    .border(
                        width = 1.5.dp,
                        color = if (selectedLocalReceiptUri != null || existingSavedReceipt.isNotBlank()) {
                            StatusOnlineGreen
                        } else {
                            DarkSurfaceVariant
                        },
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable(enabled = !isBusy) {
                        receiptSourceLauncher.openSourceDialog()
                    }
                    .testTag("receipt_image_preview_box"),
                contentAlignment = Alignment.Center
            ) {
                when {
                    selectedLocalReceiptUri != null -> {
                        AsyncImage(
                            model = selectedLocalReceiptUri,
                            contentDescription = "معاينة صورة الإيصال قبل الإرسال",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    existingSavedReceipt.isNotBlank() -> {
                        StorageAsyncImage(
                            pathOrUrl = existingSavedReceipt,
                            contentDescription = "صورة الإيصال السابقة",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    else -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(20.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(DarkSurfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AddAPhoto,
                                    contentDescription = "رفع صورة الإيصال",
                                    tint = HeroYellow,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "اضغط لتصوير الإيصال أو اختياره من المعرض 📷",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                            Text(
                                text = "تأكد من وضوح رقم الطلب وتفاصيل المبلغ",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Explicit Camera + Gallery action buttons (48dp touch targets)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { receiptSourceLauncher.captureFromCamera() },
                    enabled = !isBusy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("receipt_camera_button")
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "تصوير بالكاميرا", tint = HeroYellow, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("الكاميرا", color = Color.White, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { receiptSourceLauncher.pickFromGallery() },
                    enabled = !isBusy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("receipt_gallery_button")
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = "اختيار من المعرض", tint = HeroYellow, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("المعرض", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 4. Optional Notes
            Text(
                text = "ملاحظات إضافية (اختياري)",
                style = MaterialTheme.typography.labelMedium,
                color = Color.LightGray
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = notesInput,
                onValueChange = { notesInput = it },
                placeholder = { Text("أي تفاصيل بخصوص الإيصال أو العميل...", color = Color.Gray) },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.EditNote, contentDescription = null, tint = Color.Gray)
                },
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = HeroYellow,
                    unfocusedBorderColor = DarkSurfaceVariant,
                    focusedContainerColor = DarkSurface,
                    unfocusedContainerColor = DarkSurface,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("receipt_notes_input")
            )

            // Upload Progress / Retry / Error Banner
            if (receiptUploadState !is ImageUploadState.Idle) {
                Spacer(modifier = Modifier.height(12.dp))
                UploadStatusBanner(
                    state = receiptUploadState,
                    onRetry = if (selectedLocalReceiptUri != null) submitAction else null,
                    tagPrefix = "receipt_upload"
                )
            }

            // Form Validation Error Message
            AnimatedVisibility(visible = validationError != null) {
                Column {
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = ExpressRed.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = ExpressRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = validationError ?: "",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = ExpressRed
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Submit Button
            Button(
                onClick = submitAction,
                enabled = !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("submit_receipt_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ExpressRed,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "جاري رفع الإيصال وحفظ البيانات...",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "رفع الإيصال وإرسال البيانات للتاجر ✓",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}
