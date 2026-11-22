package com.localmusic.player

import android.content.SharedPreferences
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.json.JSONArray

internal class FixtureWishlist(private val settings: SharedPreferences) {
    var queries by mutableStateOf(
        JSONArray(settings.getString("wishlist", "[]")!!).let { saved ->
            List(saved.length()) { saved.getString(it) }
        }
    )
        private set
    private val matches = mutableStateMapOf<String, List<FixtureTrack>>()

    fun matchesFor(query: String) = matches[query].orEmpty()

    fun save(previous: String?, query: String) {
        update(if (previous == null) queries + query else queries.map { if (it == previous) query else it })
    }

    fun remove(query: String) = update(queries - query)

    private fun update(next: List<String>) {
        settings.edit().putString("wishlist", JSONArray(next).toString()).apply()
        matches.keys.retainAll(next.toSet())
        queries = next
    }

    suspend fun refresh() {
        val requested = queries
        val (query, tracks) = SoulseekFixture.wishlistPoll(requested) ?: return
        if (queries === requested && query in queries && tracks.isNotEmpty()) {
            matches[query] = (tracks + matchesFor(query)).distinct().take(100)
        }
    }

    fun endSession() = matches.clear()
}

@Composable
internal fun FixtureWishlistPanel(
    wishlist: FixtureWishlist,
    canInspect: Boolean,
    canDownload: Boolean,
    chooseTracks: (List<FixtureTrack>) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    val query = viewing
    if (query != null && wishlist.matchesFor(query).isNotEmpty()) {
        TextButton(onClick = { viewing = null }) { Text("Back to Wishlist") }
        Text(query)
        key(query) {
            FixtureSearch(canInspect, canDownload, chooseTracks, wishlistMatches = wishlist.matchesFor(query))
        }
        return
    }

    val trimmed = draft.trim()
    val duplicate = wishlist.queries.any { it != editing && it.equals(trimmed, ignoreCase = true) }
    OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("Wishlist search") }, isError = duplicate,
        supportingText = if (duplicate) ({ Text("This search is already saved.") }) else null)
    PlayerActions {
        Button(enabled = trimmed.isNotEmpty() && !duplicate, onClick = {
            wishlist.save(editing, trimmed)
            editing = null
            draft = ""
        }) { Text(if (editing == null) "Save search" else "Save changes") }
        if (editing != null) TextButton(onClick = {
            editing = null
            draft = ""
        }) { Text("Cancel edit") }
    }
    wishlist.queries.forEach { saved ->
        val count = wishlist.matchesFor(saved).size
        Text(saved)
        PlayerActions {
            if (count > 0) TextButton(onClick = { viewing = saved }) {
                Text(if (count == 1) "View 1 match" else "View $count matches")
            }
            TextButton(onClick = {
                viewing = null
                editing = saved
                draft = saved
            }) { Text("Edit") }
            TextButton(onClick = {
                viewing = null
                wishlist.remove(saved)
                if (editing == saved) {
                    editing = null
                    draft = ""
                }
            }) { Text("Remove") }
        }
    }
}
