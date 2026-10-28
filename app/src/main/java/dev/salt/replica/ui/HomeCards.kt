package dev.salt.replica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.salt.replica.playback.Track

data class HomeCardColors(val surface: Color, val ink: Color) {
    val secondary get() = ink.copy(alpha = .76f)
}

internal fun homePlayingColors(dark: Boolean) = if (dark)
    HomeCardColors(Color(0xFF29384A), Color(0xFFD8E6F6))
else HomeCardColors(Color(0xFFDDE7F2), Color(0xFF334C6B))

internal fun homeFavoriteColors(dark: Boolean) = if (dark)
    HomeCardColors(Color(0xFF75334D), Color(0xFFFFE7EF))
else HomeCardColors(Color(0xFFEAC5D0), Color(0xFF74364C))

internal fun homeFavoriteHeaderColor(dark: Boolean) =
    if (dark) Color(0xFFC84270) else Color(0xFFE24E78)

@Composable
internal fun HomePlayingContent(track: Track?, colors: HomeCardColors, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val artworkSize = (maxHeight - 94.dp).coerceIn(28.dp, 52.dp)
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayArrow, null, tint = colors.ink, modifier = Modifier.size(16.dp))
                Text("正在播放", color = colors.ink, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 4.dp))
            }
            Spacer(Modifier.weight(1f))
            Artwork(track?.artwork, Modifier.size(artworkSize).clip(RoundedCornerShape(12.dp)))
            Text(track?.title ?: "暂无歌曲", color = colors.ink, fontSize = 13.sp, lineHeight = 18.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp))
            Text(track?.artist?.ifBlank { "点击添加音乐" } ?: "点击添加音乐", color = colors.secondary,
                fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** The home tile and its returning overlay use the same layout and colors. */
@Composable
internal fun HomeFavoriteContent(count: Int, colors: HomeCardColors, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(colors.surface).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Favorite, null, tint = colors.ink, modifier = Modifier.size(15.dp))
            Text("我喜欢", color = colors.ink, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 4.dp))
        }
        Spacer(Modifier.weight(1f))
        Text("$count", color = colors.ink, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold)
        Text("首收藏歌曲", color = colors.secondary, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 2.dp))
        Text("查看全部", color = colors.secondary, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 8.dp))
    }
}
