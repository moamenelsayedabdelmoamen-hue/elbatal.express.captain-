package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ElectricScooter
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.example.ui.theme.ExpressRed
import com.example.ui.theme.HeroYellow

data class AvatarPreset(
    val id: Int,
    val title: String,
    val icon: ImageVector,
    val bgGradientColor: Color
)

val captainAvatarPresets = listOf(
    AvatarPreset(0, "سكوتر بطل", Icons.Default.TwoWheeler, ExpressRed),
    AvatarPreset(1, "دراجة نارية", Icons.Default.ElectricScooter, HeroYellow),
    AvatarPreset(2, "دراجة هواء", Icons.Default.DirectionsBike, Color(0xFF00C853)),
    AvatarPreset(3, "سيارة تبريد", Icons.Default.DirectionsCar, Color(0xFF29B6F6)),
    AvatarPreset(4, "شاحنة توصيل", Icons.Default.LocalShipping, Color(0xFFAA00FF)),
    AvatarPreset(5, "كابتن بريميوم", Icons.Default.Person, Color(0xFFFF6D00))
)

@Composable
fun CaptainAvatarView(
    presetIndex: Int = 0,
    modifier: Modifier = Modifier,
    sizeDp: Int = 64,
    avatarUri: String = ""
) {
    val preset = captainAvatarPresets.getOrElse(presetIndex) { captainAvatarPresets[0] }
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(preset.bgGradientColor)
            .border(2.dp, HeroYellow, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (avatarUri.isNotBlank()) {
            StorageAsyncImage(
                pathOrUrl = avatarUri,
                contentDescription = "الصورة الشخصية للكابتن",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                fallback = {
                    Icon(
                        imageVector = preset.icon,
                        contentDescription = preset.title,
                        tint = Color.White,
                        modifier = Modifier.size((sizeDp * 0.55).dp)
                    )
                }
            )
        } else {
            Icon(
                imageVector = preset.icon,
                contentDescription = preset.title,
                tint = Color.White,
                modifier = Modifier.size((sizeDp * 0.55).dp)
            )
        }
    }
}

@Composable
fun AvatarSelectionGrid(
    selectedPresetIndex: Int,
    onSelectPreset: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "اختر الصورة الشخصية للكابتن:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            captainAvatarPresets.forEach { preset ->
                val isSelected = selectedPresetIndex == preset.id
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(preset.bgGradientColor)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) HeroYellow else Color.Gray.copy(alpha = 0.4f),
                            shape = CircleShape
                        )
                        .clickable { onSelectPreset(preset.id) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = preset.icon,
                        contentDescription = preset.title,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "تم الاختيار",
                                tint = HeroYellow,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
