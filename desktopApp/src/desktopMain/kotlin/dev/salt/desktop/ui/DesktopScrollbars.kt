package dev.salt.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun desktopScrollbarStyle() = ScrollbarStyle(
    minimalHeight = 28.dp, thickness = 7.dp, shape = RoundedCornerShape(4.dp), hoverDurationMillis = 120,
    unhoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .28f),
    hoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f),
)

@Composable
internal fun ScrollableList(modifier: Modifier = Modifier, state: LazyListState = rememberLazyListState(),
                            contentPadding: PaddingValues = PaddingValues(bottom = 20.dp),
                            content: LazyListScope.() -> Unit) {
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 14.dp), state = state, contentPadding = contentPadding, content = content)
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = desktopScrollbarStyle())
    }
}

@Composable
internal fun ScrollableColumn(modifier: Modifier = Modifier, state: ScrollState = rememberScrollState(),
                              content: @Composable ColumnScope.() -> Unit) {
    Box(modifier) {
        Column(Modifier.padding(end = 14.dp).verticalScroll(state), content = content)
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = desktopScrollbarStyle())
    }
}
