package com.localmusic.core

import java.util.UUID

fun playlistFileChecks() {
    var count = 0
    fun verify(ok: Boolean) { count++; check(ok) { "Playlist file check $count failed" } }
    fun rejects(action: () -> Unit) { var rejected = false; try { action() } catch (_: IllegalArgumentException) { rejected = true }; verify(rejected) }
    val a = TrackId(UUID(0, 1)); val b = TrackId(UUID(0, 2))
    val m3u = "\uFEFF#EXTM3U\r\n#EXTINF:1,Song\r\nMusic/বাংলা.flac\r\n\r\nMusic/বাংলা.flac\n"
    verify(PlaylistFiles.parse(m3u, PlaylistFileFormat.M3U8) == listOf("Music/বাংলা.flac", "Music/বাংলা.flac"))
    verify(PlaylistFiles.parse("[playlist]\nFile2=b.flac\nTitle1=x\nFile1=a.flac\nNumberOfEntries=2\nVersion=2", PlaylistFileFormat.PLS) == listOf("a.flac", "b.flac"))
    rejects { PlaylistFiles.parse("[playlist]\nFile1=a\nFile1=b", PlaylistFileFormat.PLS) }
    rejects { PlaylistFiles.parse("[playlist]\nFile1=a\nNumberOfEntries=2", PlaylistFileFormat.PLS) }
    rejects { PlaylistFiles.parse("[playlist]\nFile2=a", PlaylistFileFormat.PLS) }
    rejects { PlaylistFiles.parse("#EXTM3U\n#EXT-X-TARGETDURATION:10\na.ts", PlaylistFileFormat.M3U8) }
    rejects { PlaylistFiles.parse("a\u0000b", PlaylistFileFormat.M3U) }
    rejects { PlaylistFiles.parse((1..5001).joinToString("\n") { "a.flac" }, PlaylistFileFormat.M3U) }
    val songs = listOf(PlaylistFileSong(a, listOf("content://media/1", "/storage/emulated/0/Music/Album/a.flac")),
        PlaylistFileSong(b, listOf("content://media/2", "/storage/ABCD/Music/Other/a.flac")))
    fun matched(vararg refs: String) = PlaylistFiles.match(refs.toList(), songs)
    verify(matched("content://media/1").single().id == a)
    verify(matched("Music/Album/a.flac", "Music/Album/a.flac").map { it.id } == listOf(a, a))
    verify(matched("C:\\Music\\Album\\a.flac").single().id == null)
    verify(matched("a.flac").single().reason == "Ambiguous")
    verify(matched("missing.flac").single().reason == "Missing")
    verify(matched("https://host/a.flac").single().reason == "Unsupported link")
    verify(matched("content://other/a.flac").single().id == null)
    verify(matched("file:///storage/emulated/0/Music/Album/a.flac").single().id == a)
    verify(matched("./Music/Album/a.flac").single().id == a)
    verify(matched("../Music/Album/a.flac").single().id == null)
    verify(PlaylistFiles.match(listOf("../Album/a.flac"), songs, "/storage/emulated/0/Music/Playlists/test.m3u").single().id == a)
    verify(PlaylistFiles.match(listOf("a.flac"), songs, "/storage/emulated/0/Music/Missing/test.m3u").single().id == null)
    val entries = listOf(PlaylistFileEntry("Music/বাংলা.flac", "Title\nFile2=bad", 2300), PlaylistFileEntry("Music/বাংলা.flac", "Again", 0))
    for (format in PlaylistFileFormat.entries) {
        val encoded = PlaylistFiles.encode(entries, format)
        verify(PlaylistFiles.parse(encoded, format) == entries.map { it.location })
        verify(!encoded.contains("\nFile2=bad"))
        verify(PlaylistFiles.parse(PlaylistFiles.encode(emptyList(), format), format).isEmpty())
    }
    rejects { PlaylistFiles.encode(listOf(PlaylistFileEntry("a\nb", "x", 0)), PlaylistFileFormat.M3U8) }
    rejects { PlaylistFiles.parse("a".repeat(8193), PlaylistFileFormat.M3U) }
    rejects { PlaylistFiles.parse("a".repeat(PlaylistFiles.MAX_BYTES + 1), PlaylistFileFormat.M3U) }
    rejects { PlaylistFiles.encode(listOf(PlaylistFileEntry("a".repeat(8190), "x", 0)), PlaylistFileFormat.PLS) }
    println("Playlist file checks passed: $count assertions (formats, order, duplicates, Unicode, ambiguous/missing links, limits).")
}
