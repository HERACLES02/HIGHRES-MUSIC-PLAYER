package com.localmusic.player

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun FixtureChatPanel(chat: FixtureChat, online: Boolean) {
    var private by remember { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, private) {
        fun update() {
            val resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            chat.visible(resumed && private, resumed && !private)
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose {
            lifecycle.removeObserver(observer)
            chat.visible(false, false)
        }
    }
    val active = online && chat.connected && !chat.busy
    PlayerActions {
        FilterChip(private, { private = true }, label = { Text(chatLabel("Private", chat.privateUnread)) })
        FilterChip(!private, { private = false }, label = { Text(chatLabel("Room", chat.roomUnread)) })
    }
    if (chat.status.isNotEmpty()) Text(chat.status)
    if (online && !chat.connected) Text("Chat connection unavailable. Check Wi-Fi or reconnect.")
    if (private) {
        PrivateChat(chat, active)
    } else {
        RoomChat(chat, active)
    }
}

@Composable
private fun PrivateChat(chat: FixtureChat, active: Boolean) {
    val recent = chat.privateMessages.asReversed().groupBy { it.user }
    if (recent.isNotEmpty()) {
        PlayerActions {
            recent.forEach { (name, messages) ->
                FilterChip(chat.privateUser == name, { chat.openPrivate(name) },
                    label = { Text(chatLabel(name, messages.count { it.unread })) })
            }
        }
    }
    val name = chat.privateUser
    if (name == null) {
        var user by remember { mutableStateOf("") }
        OutlinedTextField(user, { user = it.take(FixtureChat.MAX_NAME) }, Modifier.fillMaxWidth(),
            singleLine = true, label = { Text("Username") })
        TextButton(enabled = user.isNotBlank(), onClick = { chat.openPrivate(user.trim()) }) {
            Text("Open conversation")
        }
    } else {
        if (name !in recent) Text("Conversation with $name")
        TextButton(onClick = { chat.openPrivate(null) }) { Text("New conversation") }
        MessageComposer(name, active) { text, done -> chat.sendPrivate(name, text, done) }
        recent[name].orEmpty().forEach { message ->
            Text("${if (message.outgoing) "You" else name}: ${message.text}")
        }
    }
}

@Composable
private fun RoomChat(chat: FixtureChat, active: Boolean) {
    val room = chat.room
    if (room == null) {
        var name by remember { mutableStateOf("") }
        OutlinedTextField(name, { name = it.take(FixtureChat.MAX_NAME) }, Modifier.fillMaxWidth(),
            singleLine = true, label = { Text("Public room name") })
        Button(enabled = active && name.isNotBlank(), onClick = { chat.join(name.trim()) { name = "" } }) {
            Text("Join room")
        }
    } else {
        Text(if (chat.joined) room else "Joining $room…")
        TextButton(enabled = active, onClick = { chat.leave(room) }) { Text("Leave room") }
        if (chat.joined) {
            MessageComposer(room, active) { text, done -> chat.sendRoom(room, text, done) }
            chat.roomMessages.asReversed().forEach { (sender, text) -> Text("$sender: $text") }
        }
    }
}

@Composable
private fun MessageComposer(target: String, active: Boolean, send: (String, () -> Unit) -> Unit) {
    var draft by remember(target) { mutableStateOf("") }
    OutlinedTextField(draft, { draft = it.take(FixtureChat.MAX_TEXT) }, Modifier.fillMaxWidth(),
        enabled = active, label = { Text("Message") }, maxLines = 4)
    Button(enabled = active && draft.isNotBlank(), onClick = {
        val sent = draft
        send(sent) { if (draft == sent) draft = "" }
    }) { Text("Send") }
}

internal fun chatLabel(name: String, unread: Int) =
    if (unread > 0) "$name ($unread unread)" else name
