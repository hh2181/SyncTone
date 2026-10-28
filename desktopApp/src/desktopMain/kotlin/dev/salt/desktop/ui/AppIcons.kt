package dev.salt.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import org.w3c.dom.Element
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

private val icons = ConcurrentHashMap<String, ImageVector>()
private const val android = "http://schemas.android.com/apk/res/android"

fun appIcon(name: String): ImageVector = icons.getOrPut(name) {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }
    val document = requireNotNull(object {}.javaClass.getResourceAsStream("/icons/$name.xml")) {
        "Missing app icon: $name"
    }.use { factory.newDocumentBuilder().parse(it) }
    val root = document.documentElement
    val builder = ImageVector.Builder(name, 24.dp, 24.dp,
        root.getAttributeNS(android, "viewportWidth").toFloat(),
        root.getAttributeNS(android, "viewportHeight").toFloat())
    val paths = root.getElementsByTagName("path")
    for (i in 0 until paths.length) {
        val path = paths.item(i) as Element
        val fill = path.getAttributeNS(android, "fillColor")
        val stroke = path.getAttributeNS(android, "strokeColor")
        builder.addPath(
            pathData = PathParser().parsePathString(path.getAttributeNS(android, "pathData")).toNodes(),
            fill = if (fill == "#00000000") null else SolidColor(Color.Black),
            fillAlpha = path.getAttributeNS(android, "fillAlpha").toFloatOrNull() ?: 1f,
            stroke = if (stroke.isBlank() || stroke == "#00000000") null else SolidColor(Color.Black),
            strokeAlpha = path.getAttributeNS(android, "strokeAlpha").toFloatOrNull() ?: 1f,
            strokeLineWidth = path.getAttributeNS(android, "strokeWidth").toFloatOrNull() ?: 0f,
            strokeLineCap = when (path.getAttributeNS(android, "strokeLineCap")) {
                "round" -> StrokeCap.Round; "square" -> StrokeCap.Square; else -> StrokeCap.Butt
            },
            strokeLineJoin = when (path.getAttributeNS(android, "strokeLineJoin")) {
                "round" -> StrokeJoin.Round; "bevel" -> StrokeJoin.Bevel; else -> StrokeJoin.Miter
            },
            pathFillType = if (path.getAttributeNS(android, "fillType") == "evenOdd") PathFillType.EvenOdd else PathFillType.NonZero,
        )
    }
    builder.build()
}
