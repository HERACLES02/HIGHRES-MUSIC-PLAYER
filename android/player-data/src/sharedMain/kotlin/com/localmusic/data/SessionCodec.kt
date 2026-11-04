package com.localmusic.data

import com.localmusic.core.*
import java.util.UUID
import kotlinx.serialization.json.*

class UnsupportedSessionVersion(val version: Int) : IllegalArgumentException("Unsupported session version $version")

/** Explicit wire format, independent of Kotlin property names and with no autoplay field. */
object SessionCodec {
    const val MAX_CHARS = 32 * 1024 * 1024
    private fun id(value: String): UUID = UUID.fromString(value).also {
        require(it.toString().equals(value, ignoreCase = true)) { "Noncanonical UUID" }
    }
    private fun entry(value: JsonElement): QueueEntry {
        val pair = value.jsonArray; require(pair.size == 2)
        return QueueEntry(EntryId(id(pair[0].jsonPrimitive.content)), TrackId(id(pair[1].jsonPrimitive.content)))
    }
    private fun entries(root: JsonObject, key: String, limit: Int): List<QueueEntry> = root.getValue(key).jsonArray.also {
        require(it.size <= limit)
    }.map(::entry)
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.also { require(!it.isString) }
    private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.also { require(!it.isString) }.boolean
    private fun encodeEntries(values: List<QueueEntry>) = JsonArray(values.map {
        JsonArray(listOf(JsonPrimitive(it.id.value.toString()), JsonPrimitive(it.trackId.value.toString())))
    })

    fun encode(snapshot: QueueSnapshot): String {
        require(ListeningQueue().restore(snapshot)) { "Invalid queue snapshot" }
        return buildJsonObject {
            put("version", snapshot.version)
            put("collection", encodeEntries(snapshot.collection)); put("order", encodeEntries(snapshot.order))
            put("cursor", snapshot.cursor); put("history", encodeEntries(snapshot.history))
            put("historyCursor", snapshot.historyCursor); put("manual", encodeEntries(snapshot.manual))
            put("heard", JsonArray(snapshot.heard.map { JsonPrimitive(it.value.toString()) }))
            put("shuffle", snapshot.shuffle); put("repeat", snapshot.repeat.name)
            put("positionMs", snapshot.positionMs); put("completed", snapshot.completed)
            snapshot.origin?.let { put("origin", it) }
        }.toString().also { require(it.length <= MAX_CHARS) { "Snapshot too large" } }
    }

    fun decode(text: String): QueueSnapshot = try {
        decodeFields(text)
    } catch (error: NoSuchElementException) {
        throw IllegalArgumentException("Incomplete session snapshot", error)
    } catch (error: IllegalStateException) {
        throw IllegalArgumentException("Invalid session snapshot shape", error)
    }

    private fun decodeFields(text: String): QueueSnapshot {
        require(text.length <= MAX_CHARS) { "Snapshot too large" }
        val root = Json.parseToJsonElement(text).jsonObject
        val version = root.number("version").int
        if (version != 1) throw UnsupportedSessionVersion(version)
        val heard = root.getValue("heard").jsonArray.also { require(it.size <= 100000) }
            .map { EntryId(id(it.jsonPrimitive.content)) }
        require(heard.toSet().size == heard.size)
        val origin = root["origin"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.also { require(it.isString) }?.content
        val result = QueueSnapshot(version, entries(root, "collection", 100000), entries(root, "order", 100000),
            root.number("cursor").int, entries(root, "history", 1000),
            root.number("historyCursor").int, entries(root, "manual", 10000), heard.toSet(),
            root.flag("shuffle"), RepeatMode.valueOf(root.getValue("repeat").jsonPrimitive.content),
            root.number("positionMs").long, root.flag("completed"), origin)
        require(ListeningQueue().restore(result)) { "Invalid queue snapshot" }
        return result
    }
}
