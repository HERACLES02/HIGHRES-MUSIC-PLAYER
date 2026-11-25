package com.localmusic.player

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class FixturePrivateMessage(
    val user: String,
    val text: String,
    val outgoing: Boolean,
    val unread: Boolean = false,
    val id: Long = 0,
    val timestamp: Long = 0,
) {
    fun encode() = JSONObject().put("user", user).put("text", text).put("outgoing", outgoing)
        .put("unread", unread).put("id", id).put("timestamp", timestamp)
}

internal class FixtureChat(
    app: Context,
    private val connectivity: ConnectivityManager,
    private val scope: CoroutineScope,
) {
    private val history = app.getSharedPreferences("fixture-chat", Context.MODE_PRIVATE)
    var privateMessages by mutableStateOf(readHistory())
        private set
    var privateUser by mutableStateOf<String?>(null)
        private set
    var room by mutableStateOf<String?>(null)
        private set
    var roomMessages by mutableStateOf(emptyList<Pair<String, String>>())
        private set
    var roomUnread by mutableStateOf(0)
        private set
    var joined by mutableStateOf(false)
        private set
    var connected by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    private var generation: Long? = null
    private var joinDeadline = 0L
    private var showingPrivate = false
    private var showingRoom = false

    val privateUnread get() = privateMessages.count { it.unread }

    fun visible(private: Boolean, room: Boolean) {
        showingPrivate = private
        showingRoom = room
        if (private) markPrivateRead()
        if (room) roomUnread = 0
    }

    fun openPrivate(user: String?) {
        privateUser = user
        status = ""
        if (showingPrivate) markPrivateRead()
    }

    fun endSession() {
        connected = false
        generation = null
        clearRoom()
        status = ""
    }

    suspend fun refresh() {
        if (connectivity.wifiNetwork() == null) {
            connected = false
            return
        }
        // The engine drains its inbox on poll. Apply the batch even if going offline
        // cancels the session watcher while the native call is in flight.
        withContext(NonCancellable) {
            val batch = SoulseekFixture.chatPoll()
            if (batch == null) {
                endSession()
                return@withContext
            }
            val current = batch.getLong("generation")
            if (generation != current) {
                clearRoom()
                generation = current
            }
            connected = true
            val received = batch.getJSONArray("private")
            val updated = privateMessages.toMutableList()
            for (i in 0 until received.length()) {
                val row = received.getJSONObject(i)
                val user = row.getString("user").take(MAX_NAME)
                val id = row.getLong("id")
                val timestamp = row.getLong("timestamp")
                if (updated.any { !it.outgoing && it.user == user && it.id == id && it.timestamp == timestamp }) {
                    continue
                }
                updated.add(FixturePrivateMessage(user, row.getString("text").take(MAX_TEXT), false,
                    unread = !showingPrivate || privateUser != user, id = id, timestamp = timestamp))
            }
            if (received.length() > 0) save(updated)
            val events = batch.getJSONArray("rooms")
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                if (event.getString("room") != room) continue
                when (event.getString("kind")) {
                    "joined" -> {
                        joined = true
                        status = ""
                    }
                    "unavailable" -> {
                        clearRoom()
                        status = "That room is unavailable."
                    }
                    "message" -> {
                        roomMessages = (roomMessages + (event.getString("user").take(MAX_NAME) to
                            event.getString("text").take(MAX_TEXT))).takeLast(MAX_MESSAGES)
                        if (!showingRoom && !event.getBoolean("outgoing")) roomUnread++
                    }
                }
            }
            if (room != null && !joined && SystemClock.elapsedRealtime() >= joinDeadline) {
                status = "The room has not confirmed your join. Leave the room to try again."
            }
        }
    }

    fun join(name: String, success: () -> Unit) {
        if (busy || !connected) return
        clearRoom()
        room = name
        joinDeadline = SystemClock.elapsedRealtime() + 10_000
        action("chatJoin", name, complete = success)
    }

    fun leave(name: String) = action("chatLeave", name, complete = ::clearRoom)

    fun sendPrivate(user: String, text: String, success: () -> Unit) =
        action("chatPrivate", user, text) {
            save(privateMessages + FixturePrivateMessage(user, text, outgoing = true))
            success()
        }

    fun sendRoom(name: String, text: String, success: () -> Unit) =
        action("chatRoom", name, text, success)

    private fun action(operation: String, name: String, text: String? = null, complete: () -> Unit) {
        val session = generation ?: return
        if (busy || !connected) return
        busy = true
        status = ""
        scope.launch {
            try {
                check(connectivity.wifiNetwork() != null) { "Connect to Wi-Fi to use chat." }
                SoulseekFixture.chatAction(operation, session, name, text)
                if (session == generation || operation == "chatPrivate") complete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (operation == "chatJoin" && session == generation) clearRoom()
                status = e.message ?: "Chat could not complete the request."
            } finally {
                busy = false
            }
        }
    }

    private fun clearRoom() {
        room = null
        joined = false
        roomMessages = emptyList()
        roomUnread = 0
    }

    private fun markPrivateRead() {
        if (privateMessages.any { it.user == privateUser && it.unread }) {
            save(privateMessages.map { if (it.user == privateUser) it.copy(unread = false) else it })
        }
    }

    private fun save(messages: List<FixturePrivateMessage>) {
        privateMessages = messages.takeLast(MAX_MESSAGES)
        history.edit().putString("messages", JSONArray(privateMessages.map { it.encode() }).toString()).apply()
    }

    private fun readHistory(): List<FixturePrivateMessage> = try {
        val rows = JSONArray(history.getString("messages", "[]"))
        List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            FixturePrivateMessage(row.getString("user"), row.getString("text"), row.getBoolean("outgoing"),
                row.getBoolean("unread"), row.getLong("id"), row.getLong("timestamp"))
        }
    } catch (_: org.json.JSONException) {
        emptyList()
    }

    companion object {
        const val MAX_NAME = 128
        const val MAX_TEXT = 2_000
        private const val MAX_MESSAGES = 100
    }
}
