# Local Flask Music Player

Local high-resolution music player web app built with Flask.

## Features

- Scans a local music folder from `MUSIC_DIR`
- Streams MP3, FLAC, M4A, AAC, OGG, and WAV files
- Supports browser seeking with range requests
- Reads title, artist, album, and embedded cover art with Mutagen
- Login-protected web UI
- Searchable library
- Folder views based on your local music folder structure
- Spotify-inspired now-playing detail panel with large cover art
- Like/unlike songs
- Create and delete named playlists
- Add and remove tracks from playlists
- Realtime lyrics from `.lrc` files or embedded lyric tags
- Add or edit synced `.lrc` lyrics from the web UI
- Full-screen lyrics view with blurred album art background
- Metadata editor for title, artist, and album
- Batch metadata editing for selected tracks
- Auto-fill metadata from filename and folder structure
- Upload custom cover images for playlists and folders
- Upload custom cover images for individual tracks
- Upload a custom app background image, GIF, or video
- Manual queue with Play Next, Queue, and Clear Queue
- Smart Mixes such as Random 25 and library cleanup views
- Persistent play history with play count, recently played, and resume position
- Continue Listening, Recently Played, and Most Played smart views
- Chunked track rendering with debounced search and Load more controls for larger libraries
- Artist and album detail views
- Internet artist info from MusicBrainz and Wikipedia with local caching
- Manual artist match picker when automatic internet artist info chooses the wrong result
- Home artist and album cards with full artist/album index pages
- Fullscreen now-playing view
- Browser media key support through the Media Session API
- Toast notifications for common actions
- Multi-select visible tracks and batch like, unlike, add to playlist, play next, or queue
- Export and restore app backups as zip files
- Settings page for storage paths, backup/restore, cache controls, and security status
- CSRF protection for app-changing requests and basic browser hardening headers
- Login throttling, localhost-only default binding, and upload size limits
- Saves likes and playlists in `playlist.json`

## Setup

Install dependencies inside the project virtual environment:

```powershell
cd D:\music_player
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

Edit `run.ps1` and point `MUSIC_DIR` at your music folder:

```powershell
$env:MUSIC_DIR="D:\music"
```

Your folder can contain nested album/artist folders. The app scans recursively.

## Run

```powershell
cd D:\music_player
.\run.ps1
```

Open:

```text
http://127.0.0.1:5000
```

Development login:

```text
Username: admin
Password: change-me
```

## Data

Likes and playlists are saved here:

```text
D:\music_player\playlist.json
```

Playlist and folder cover uploads are saved under:

```text
D:\music_player\artwork\
```

Individual track cover overrides and the app background are also saved under `artwork`.

Listening history is saved in `playlist.json` with the rest of the app state.

Fetched artist info is also cached in `playlist.json`, so the app does not hit internet metadata services every time you open an artist or play a song.

Use **Export** to download a backup zip containing `playlist.json` and the `artwork/` folder. Use **Restore** to upload that zip and replace the current local app state.

The **Settings** page shows the active music folder, state file, artwork folder, backup controls, artist info cache controls, and local security status.

Music files stay in your own music folder. The app does not copy or modify your songs.

## Lyrics

For synced realtime lyrics, place an `.lrc` file next to the song with the same filename:

```text
D:\music\Album\song.flac
D:\music\Album\song.lrc
```

The app also checks embedded lyric tags when no `.lrc` file exists. `.lrc` files are preferred because they include timestamps for realtime highlighting.

Use the **LRC** button on a track card to paste synced lyrics or upload an `.lrc` file. Saving writes a sidecar `.lrc` file next to the song.

The lyrics editor can insert the current playback timestamp, which makes manual LRC timing easier while the song is playing.

## Metadata Editing

Use the **Edit** button on a track to update:

```text
Title
Artist
Album
```

The app writes these tags directly to the audio file, then reloads the library. Keep a backup of your music folder if you are testing metadata edits on important files.

Use **Select visible** or the track checkboxes, then **Metadata** in the selection bar to batch-set artist or album. Use **Auto-fill tags** to infer title from the filename, album from the parent folder, and artist from the folder above the album when available.

Use the **Cover** button on a track card to set a local cover override for that specific track. This changes the app artwork without rewriting the embedded cover inside the audio file.

Use the **Background** button in the header to set a local background image, GIF, MP4, WEBM, or MOV file.

## Internet Artist Info

Artist pages and the right-side now-playing panel can fetch public artist metadata from MusicBrainz and Wikipedia. The lookup runs from the Flask server, requires internet access on the machine running the app, and saves the result in `playlist.json`.

Use **Refresh** on an artist page to force a new lookup. Ambiguous artist names or tags like `feat.` may still match the wrong page, so keep the local metadata clean for better results.

Use **Choose match** on an artist page to pick the MusicBrainz and/or Wikipedia result manually. The selected match is cached in `playlist.json` for later use.

## Notes

- Keep this app on your local machine or private network unless you add stronger production security.
- Change the default `SECRET_KEY`, username, and password hash in `run.ps1` before letting any other device access the app.
- The Flask server binds to `127.0.0.1` by default. Set `FLASK_HOST=0.0.0.0` only if you intentionally want access from other devices on your network.
- Flask debug mode is off by default. Set `FLASK_DEBUG=1` only while actively developing.
- Turn on `SESSION_COOKIE_SECURE=1` only when serving the app over HTTPS.
- If you add new music while the app is running, click **Refresh** in the web UI.
- Change the default password before exposing the app to anyone else on your network.
