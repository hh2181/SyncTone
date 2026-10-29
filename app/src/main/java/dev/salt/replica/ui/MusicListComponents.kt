package dev.salt.replica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The statistics song list is the visual baseline for all mobile music lists. */
internal val MusicListShape = RoundedCornerShape(24.dp)
internal fun musicListCorners(first: Boolean, last: Boolean) = RoundedCornerShape(
    topStart = if (first) 24.dp else 0.dp, topEnd = if (first) 24.dp else 0.dp,
    bottomStart = if (last) 24.dp else 0.dp, bottomEnd = if (last) 24.dp else 0.dp,
)

@Composable
internal fun MusicListCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(MusicListShape).background(MaterialTheme.colorScheme.surface), content = content)
}

/** Callers supply artwork and actions; geometry and typography have a single owner. */
@Composable
internal fun MusicListRow(
    title: String,
    artist: String,
    index: Int,
    modifier: Modifier = Modifier,
    secondaryMetadata: String? = null,
    current: Boolean = false,
    artwork: @Composable (Modifier) -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().background(colors.surface)
        .padding(horizontal = 14.dp, vertical = 11.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(26.dp), contentAlignment = Alignment.CenterStart) {
            if (current) Icon(Icons.Default.PlayArrow, "当前歌曲", Modifier.size(16.dp), tint = colors.primary)
            else Text(index.toString(), fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        artwork(Modifier.size(39.dp).clip(RoundedCornerShape(9.dp)))
        Column(Modifier.weight(1f)) {
            Text(title.ifBlank { "未命名歌曲" }, fontSize = 13.sp,
                fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                color = if (current) colors.primary else colors.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(artist.ifBlank { "未知艺人" }, fontSize = 11.sp, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (secondaryMetadata != null) Text(" · $secondaryMetadata", fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1)
            }
        }
        trailing()
    }
}

@Composable
internal fun MusicListMetric(value: String, secondary: String? = null) {
    Column(Modifier.widthIn(max = 104.dp), horizontalAlignment = Alignment.End) {
        Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (secondary != null) Text(secondary, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
internal fun MusicListDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.background(MaterialTheme.colorScheme.surface).padding(start = 43.dp, end = 14.dp))
}
