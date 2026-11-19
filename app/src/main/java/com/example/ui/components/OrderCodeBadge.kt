package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.HeroYellow

/**
 * Displays the partner/Firestore order code(s) cleanly without reintroducing raw internal UUID order numbers.
 */
@Composable
fun OrderCodeBadge(
    orderCode: String,
    modifier: Modifier = Modifier,
    badgeTag: String = "order_code_badge"
) {
    val cleanCode = orderCode.trim().trimStart('#').trim()
    if (cleanCode.isBlank()) return

    Surface(
        modifier = modifier.testTag(badgeTag),
        shape = RoundedCornerShape(10.dp),
        color = HeroYellow.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, HeroYellow.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.QrCode2,
                contentDescription = null,
                tint = HeroYellow,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "كود الطلب: $cleanCode",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.4.sp
                ),
                color = HeroYellow,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
