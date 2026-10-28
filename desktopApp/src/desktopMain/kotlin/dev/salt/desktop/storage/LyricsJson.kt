package dev.salt.desktop.storage

import dev.salt.replica.lyrics.*
import kotlinx.serialization.json.*

internal fun encodeLyrics(lyrics: Lyrics): String = buildJsonObject {
    put("source", lyrics.source)
    put("lines", JsonArray(lyrics.lines.map { line -> buildJsonObject {
        put("start", line.start); put("end", line.end)
        put("cells", encodeCells(line.cells))
        put("secondary", JsonArray(line.subLines.map { encodeCells(it.cells) }))
    } }))
}.toString()

private fun encodeCells(cells: List<LyricCell>) = JsonArray(cells.map { cell -> buildJsonObject {
    put("start", cell.start); put("end", cell.end); put("text", cell.text)
} })

internal fun decodeLyrics(text: String): Lyrics {
    val json = Json.parseToJsonElement(text).jsonObject
    fun cells(value: JsonElement) = value.jsonArray.map { item -> item.jsonObject.let {
        LyricCell(it.getValue("start").jsonPrimitive.long, it.getValue("end").jsonPrimitive.long,
            it.getValue("text").jsonPrimitive.content)
    } }
    return Lyrics(json.getValue("lines").jsonArray.map { item -> item.jsonObject.let {
        LyricLine(it.getValue("start").jsonPrimitive.long, it.getValue("end").jsonPrimitive.long,
            cells(it.getValue("cells")), it.getValue("secondary").jsonArray.map { sub -> LyricSubLine(cells(sub)) })
    } }, json.getValue("source").jsonPrimitive.content)
}
