package com.usbforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.usbforge.ui.theme.ForgeColors
import com.usbforge.ui.theme.Spacing
import com.usbforge.ui.theme.UsbForgeTheme

/**
 * Gelecekte reklam ağı bağlanacak **boş banner konteyneri**.
 *
 * ## Amaç
 * Reklam SDK'sı sonradan eklendiğinde arayüzün taşmasın diye yer ayrılmış
 * durumda. `visible = false` verildiğinde kutu tamamen kaldırılır (ölçüm
 * yapılmaz); `true` iken yer tutucu çizilir.
 *
 * ## Entegrasyon notu
 * Reklam SDK'sı eklendiğinde yalnızca [content] slotunu değiştirmek yeterlidir:
 * ```
 * BannerSlot(id = "top", height = 50.dp) { AdView(requireContext()) }
 * ```
 * Kutu boyutu reklam ağının belirlediği yüksekliğe göre ayarlanmalıdır.
 *
 * @param slot   test ve loglar için kararlı kimlik
 * @param height banner yüksekliği
 * @param label  boş durumda gösterilecek metin
 */
@Composable
fun BannerSlot(
    slot: String,
    modifier: Modifier = Modifier,
    height: Dp = 50.dp,
    label: String = "Reklam Alanı",
    visible: Boolean = true,
    content: @Composable () -> Unit = {},
) {
    if (!visible) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(ForgeColors.Slot)
            .border(
                width = 1.dp,
                color = ForgeColors.Outline,
                shape = RoundedCornerShape(6.dp),
            )
            .semantics { contentDescription = "$label ($slot)" },
        contentAlignment = Alignment.Center,
    ) {
        content()
        PlaceholderContent(label = label, slot = slot)
    }
}

/**
 * Reklam yüklenene kadar gösterilen yer tutucu.
 * Reklam eklendiğinde bu bileşen [BannerSlot] içinden kaldırılmalıdır.
 */
@Composable
private fun PlaceholderContent(label: String, slot: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = Spacing.sm),
    ) {
        Text(
            text = "$label · $slot",
            style = MaterialTheme.typography.labelSmall,
            color = ForgeColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "reklam ağı bağlanacak konteyner",
            style = MaterialTheme.typography.labelSmall,
            color = ForgeColors.Outline,
            textAlign = TextAlign.Center,
        )
    }
}

/** Alt kısımdaki iki bannerı gruplayan yardımcı. */
@Composable
fun BottomBannerPair(
    visible: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        BannerSlot(slot = "bottom-1", label = "Reklam Alanı (Alt Banner)", visible = visible)
        BannerSlot(slot = "bottom-2", label = "Reklam Alanı (Alt Banner 2)", height = 44.dp, visible = visible)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0F14)
@Composable
private fun BannerSlotPreview() {
    UsbForgeTheme {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            BannerSlot(slot = "top", label = "Reklam Alanı (Üst Banner)")
            BottomBannerPair(visible = true, onToggle = {})
        }
    }
}
