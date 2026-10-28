package dev.salt.replica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import dev.salt.buttons.SyncToneIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.salt.replica.ui.theme.HyperBlue
import dev.salt.replica.BuildConfig
import dev.salt.replica.ui.theme.HyperCard
import dev.salt.replica.ui.theme.hyperClickable

@Composable
fun SettingsScreen(
    settings: ReplicaSettings,
    onMenu: () -> Unit,
    onOpenLyrics: () -> Unit,
    modifier: Modifier
) {
    Column(
        modifier
            .padding(horizontal = 18.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onMenu, Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.Menu, "打开侧边栏",
                    tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "v${BuildConfig.VERSION_NAME}", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(Modifier.padding(start = 4.dp, top = 2.dp, bottom = 16.dp)) {
            Text(
                "SETTINGS", fontSize = 11.sp, fontWeight = FontWeight.Medium,
                letterSpacing = 1.4.sp, color = MaterialTheme.colorScheme.primary
            )
            Text("设置", fontSize = 27.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        }

        SectionLabel("外观")
        HyperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            ThemeRow("跟随系统", "跟随手机的深色模式", settings.themeMode == 0) {
                settings.themeMode = 0; settings.save()
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 16.dp))
            ThemeRow("浅色", "始终使用浅色界面", settings.themeMode == 1) {
                settings.themeMode = 1; settings.save()
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 16.dp))
            ThemeRow("深色", "始终使用深色界面", settings.themeMode == 2) {
                settings.themeMode = 2; settings.save()
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel("歌词")
        HyperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), onClick = onOpenLyrics) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("歌词排版与动画", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "字号、字重、动画曲线、模糊", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Icon(
                    Icons.Default.KeyboardArrowRight, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel("关于")
        HyperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(HyperBlue),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("S", color = androidx.compose.ui.graphics.Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("SyncTone", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("本地音乐播放器", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    "音乐与播放数据保存在本机。局域网同步仅与已配对设备交换音乐文件和播放历史。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
    )
}

@Composable
private fun ThemeRow(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).hyperClickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                .border(
                    1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check, null,
                    tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
