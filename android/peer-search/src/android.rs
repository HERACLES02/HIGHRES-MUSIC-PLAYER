use jni::JNIEnv;
use jni::objects::{JObject, JString};
use jni::sys::jstring;
use serde_json::{Value, json};
use soulseek_rs::shares::Shares;
use soulseek_rs::types::{RoomEvent, SearchResult};
use soulseek_rs::{Client, ClientSettings, DownloadStatus, PeerAddress, UploadStatus};
use std::error::Error;
use std::fs;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::{Path, PathBuf};
use std::ptr;
use std::sync::Mutex;
use std::sync::mpsc::{Receiver, RecvTimeoutError, TryRecvError};
use std::thread;
use std::time::{Duration, Instant};

type Result<T> = std::result::Result<T, Box<dyn Error>>;
const FIXTURE_MAX_BYTES: u64 = 32 * 1024 * 1024;
const FIXTURE_BYTES_PER_SECOND: u64 = 64 * 1024;
const DIAGNOSTIC_ACCOUNT: &str = "local-music-fixture";

struct Session {
    client: Option<Client>,
    credentials: Option<(String, String)>,
    generation: u64,
    download: Option<Transfer>,
    share: Option<PathBuf>,
    wishlist: Option<Wishlist>,
}

struct Wishlist {
    last_request: Instant,
    last_query: Option<String>,
    active: Option<String>,
    interrupted: Option<Value>,
}

impl Wishlist {
    fn started() -> Self {
        Self {
            last_request: Instant::now(),
            last_query: None,
            active: None,
            interrupted: None,
        }
    }

    fn snapshot(client: &Client, query: &str) -> Value {
        json!({"query": query, "tracks": search_tracks(client.get_search_results(query), false)})
    }

    fn stop_collecting(&mut self, client: &Client) {
        if let Some(query) = self.active.take() {
            let _ = client.forget_search(&query);
        }
    }

    fn before_manual_search(&mut self, client: &Client, query: &str) {
        // The engine stores one token per query, shared by both search types.
        if self.active.as_deref() == Some(query) {
            self.interrupted = Some(Self::snapshot(client, query));
            self.stop_collecting(client);
        }
    }

    fn poll(&mut self, client: &Client, queries: &[&str]) -> Result<Value> {
        if self
            .active
            .as_ref()
            .is_some_and(|query| !queries.contains(&query.as_str()))
        {
            self.stop_collecting(client);
        }
        let result = self.interrupted.take().or_else(|| {
            self.active
                .as_deref()
                .map(|query| Self::snapshot(client, query))
        });
        let interval = client.wishlist_interval();
        if !queries.is_empty() && !interval.is_zero() && self.last_request.elapsed() >= interval {
            let next = queries
                .iter()
                .position(|query| Some(*query) == self.last_query.as_deref())
                .map_or(0, |index| (index + 1) % queries.len());
            let query = queries[next];
            self.stop_collecting(client);
            client.start_wishlist_search(query)?;
            self.last_request = Instant::now();
            self.last_query = Some(query.into());
            self.active = Some(query.into());
        }
        Ok(result.unwrap_or(Value::Null))
    }
}

struct Transfer {
    user: String,
    path: String,
    size: u64,
    events: Receiver<DownloadStatus>,
    offset: Option<u64>,
}

static SESSION: Mutex<Session> = Mutex::new(Session {
    client: None,
    credentials: None,
    generation: 0,
    download: None,
    share: None,
    wishlist: None,
});

impl Session {
    fn sign_in(&mut self, username: &str, password: &str) -> Result<Value> {
        if username.trim().is_empty() || password.is_empty() {
            return Err("Enter the private fixture username and password".into());
        }
        self.credentials = None;
        self.stop(true)?;
        self.client = Some(connect(username, password)?);
        self.credentials = Some((username.into(), password.into()));
        self.generation += 1;
        self.wishlist = Some(Wishlist::started());
        Ok(Value::Null)
    }

    fn client(&mut self, diagnostic: bool) -> Result<&Client> {
        if !diagnostic && self.credentials.is_none() {
            return Err("Sign in to the private fixture first".into());
        }
        if self
            .client
            .as_ref()
            .is_some_and(|client| client.session_loss().is_some())
        {
            self.stop_sharing()?;
            self.client = None;
        }
        if self.client.is_none() {
            let (username, password) = self
                .credentials
                .as_ref()
                .map_or((DIAGNOSTIC_ACCOUNT, "local-fixture"), |(user, pass)| {
                    (user.as_str(), pass.as_str())
                });
            self.client = Some(connect(username, password)?);
            self.generation += 1;
            self.wishlist = Some(Wishlist::started());
        }
        Ok(self.client.as_ref().expect("connected fixture client"))
    }

    fn wishlist_poll(&mut self, request: &Value) -> Result<Value> {
        let Some(client) = self
            .client
            .as_ref()
            .filter(|client| client.session_loss().is_none())
        else {
            return Ok(Value::Null);
        };
        let Some(wishlist) = self.wishlist.as_mut() else {
            return Ok(Value::Null);
        };
        let queries = request["queries"]
            .as_array()
            .ok_or("Missing wishlist searches")?
            .iter()
            .filter_map(Value::as_str)
            .collect::<Vec<_>>();
        wishlist.poll(client, &queries)
    }

    fn chat(&self, operation: &str, request: &Value) -> Result<Value> {
        let client = self
            .client
            .as_ref()
            .filter(|client| client.session_loss().is_none());
        if operation == "chatPoll" {
            let Some(client) = client else {
                return Ok(Value::Null);
            };
            let private: Vec<_> = client
                .take_private_messages()
                .into_iter()
                .map(|msg| {
                    json!({
                        "id": msg.id(), "timestamp": msg.timestamp(),
                        "user": msg.username(), "text": msg.message(),
                    })
                })
                .collect();
            let rooms: Vec<_> = client
                .take_room_events()
                .into_iter()
                .filter_map(|event| match event {
                    RoomEvent::Joined { room, .. } => Some(json!({"kind": "joined", "room": room})),
                    RoomEvent::CantCreate { room } => {
                        Some(json!({"kind": "unavailable", "room": room}))
                    }
                    RoomEvent::Message {
                        room,
                        username,
                        message,
                    } => Some(json!({
                        "kind": "message", "room": room, "user": username,
                        "text": message, "outgoing": username == client.username(),
                    })),
                    _ => None,
                })
                .collect();
            return Ok(json!({"generation": self.generation, "private": private, "rooms": rooms}));
        }
        let client = client.ok_or("The chat connection ended. Go offline and reconnect.")?;
        if request["generation"].as_u64() != Some(self.generation) {
            return Err("The chat connection changed. Try again.".into());
        }
        let name = field(request, "name")?;
        match operation {
            "chatPrivate" => client.send_private_message(name, field(request, "text")?)?,
            "chatJoin" => client.join_room(name)?,
            "chatLeave" => client.leave_room(name)?,
            "chatRoom" => client.say_in_room(name, field(request, "text")?)?,
            _ => return Err("Unknown chat operation".into()),
        }
        Ok(Value::Null)
    }

    fn stop(&mut self, disconnect: bool) -> Result<Value> {
        if disconnect {
            self.stop_sharing()?;
        }
        if let Some(transfer) = &self.download {
            if let Some(client) = &self.client {
                // Cancelling deletes the partial. Removing the row instead makes
                // the worker leave its read loop with the existing bytes intact.
                let _ = client.remove_download(&transfer.user, &transfer.path);
            }
            let deadline = Instant::now() + Duration::from_secs(25);
            loop {
                match transfer
                    .events
                    .recv_timeout(deadline.saturating_duration_since(Instant::now()))
                {
                    Err(RecvTimeoutError::Disconnected) => break,
                    Err(RecvTimeoutError::Timeout) => {
                        return Err("The previous transfer is still stopping".into());
                    }
                    Ok(_) => {}
                }
            }
            // All sender clones are gone. No worker can still write this partial.
            self.download = None;
        }
        if disconnect {
            self.client = None;
            self.wishlist = None;
        }
        Ok(Value::Null)
    }

    fn start_sharing(&mut self, root: PathBuf) -> Result<Value> {
        if self.share.is_some() {
            return Err("Turn off the current share first".into());
        }
        let client = self
            .client
            .as_ref()
            .filter(|client| client.session_loss().is_none())
            .ok_or("Go online before sharing")?;
        // The picker copied these bytes into a unique, private directory.
        let result = client.set_shared_directories(vec![root.to_string_lossy().into_owned()]);
        self.share = Some(root);
        if let Err(error) = result {
            self.stop_sharing()?;
            return Err(error.into());
        }
        Ok(Value::Null)
    }

    fn stop_sharing(&mut self) -> Result<Value> {
        let Some(root) = &self.share else {
            return Ok(Value::Null);
        };
        if let Some(client) = &self.client {
            // The engine replaces its local index even if the server is gone.
            let _ = client.set_shared_directories(Vec::new());
            // Previously queued jobs retain absolute paths. Retire this
            // generation so they cannot open a file, even after a new preview.
            let retired = root.with_file_name(if root.ends_with(".withdrawn") {
                ".withdrawn-folder"
            } else {
                ".withdrawn"
            });
            let result = fs::rename(root, retired).or_else(|_| fs::remove_dir_all(root));
            for upload in client.uploads() {
                if upload.status == UploadStatus::InProgress {
                    let _ = client.cancel_upload(&upload.username, &upload.filename);
                }
            }
            if let Err(error) = result
                && error.kind() != std::io::ErrorKind::NotFound
            {
                return Err(error.into());
            }
        }
        self.share = None;
        Ok(Value::Null)
    }

    fn share_status(&self) -> Value {
        let Some(client) = self
            .client
            .as_ref()
            .filter(|client| client.session_loss().is_none())
        else {
            return json!({"sharing": false, "uploads": []});
        };
        let sharing = self.share.is_some();
        let uploads: Vec<_> = if sharing {
            client
                .uploads()
                .into_iter()
                .filter_map(|upload| {
                    let position = match upload.status {
                        UploadStatus::Queued(place) => Some(place),
                        UploadStatus::InProgress => None,
                        _ => return None,
                    };
                    Some(json!({
                        "user": upload.username, "path": upload.filename,
                        "size": upload.size, "sent": upload.bytes_sent,
                        "position": position,
                        "privileged": client.is_privileged(&upload.username),
                    }))
                })
                .collect()
        } else {
            Vec::new()
        };
        json!({"sharing": sharing, "privilegeSeconds": client.own_privilege_seconds(), "uploads": uploads})
    }

    fn start(&mut self, request: &Value) -> Result<Value> {
        let user = field(request, "user")?;
        let path = field(request, "path")?;
        let directory = field(request, "directory")?;
        let size = request["size"].as_u64().ok_or("Missing track size")?;
        let basename = path.rsplit(['/', '\\']).next().unwrap_or("");
        let diagnostic = request["diagnostic"].as_bool().unwrap_or(false);
        if !supported_track(basename, size, !diagnostic) || basename.contains(['\0', ':']) {
            return Err("The selected track has an invalid name or size".into());
        }
        let (_, events) = self
            .client(diagnostic)?
            .download(path.into(), user.into(), size, directory.into())?;
        self.download = Some(Transfer {
            user: user.into(),
            path: path.into(),
            size,
            events,
            offset: None,
        });
        Ok(Value::Null)
    }

    fn poll(&mut self) -> Result<Value> {
        let transfer = self.download.as_mut().ok_or("No fixture transfer")?;
        loop {
            match transfer.events.try_recv() {
                Ok(DownloadStatus::InProgress {
                    bytes_downloaded,
                    total_bytes,
                    ..
                }) => {
                    if total_bytes != transfer.size {
                        return Ok(json!({"status": "sizeChanged"}));
                    }
                    transfer.offset.get_or_insert(bytes_downloaded);
                }
                Ok(DownloadStatus::Completed) => {
                    return Ok(json!({"status": "complete", "offset": transfer.offset}));
                }
                Ok(DownloadStatus::Failed(reason)) => {
                    return Err(reason.unwrap_or_else(|| "Download failed".into()).into());
                }
                Ok(status) if status.is_terminal() => {
                    return Err(format!("Download ended: {status:?}").into());
                }
                Ok(_) => {}
                Err(TryRecvError::Empty) => break,
                Err(TryRecvError::Disconnected) => return Err("The fixture transfer ended".into()),
            }
        }
        let queue_position = self.client.as_ref().and_then(|client| {
            client
                .get_all_downloads()
                .into_iter()
                .find(|row| {
                    row.username == transfer.user
                        && row.filename == transfer.path
                        && matches!(row.status, DownloadStatus::Queued)
                })
                .and_then(|row| row.queue_position)
        });
        Ok(json!({"status": "pending", "offset": transfer.offset, "queuePosition": queue_position}))
    }
}

fn connect(username: &str, password: &str) -> Result<Client> {
    let mut settings = ClientSettings::new(username, password);
    settings.server_address = PeerAddress::new("127.0.0.1".into(), 45415);
    settings.listen_port = 45417;
    let mut client = Client::with_settings(settings);
    client.set_upload_slots(1);
    client.connect()?;
    if !client.login()? {
        return Err("Private fixture login was declined".into());
    }
    client.set_download_speed_limit(FIXTURE_BYTES_PER_SECOND);
    client.check_privileges()?;
    Ok(client)
}

fn share_preview(root: &Path) -> Result<Value> {
    let shares = Shares::scan(root)?;
    let mut files: Vec<_> = shares.files().iter().collect();
    files.sort_unstable_by(|a, b| a.virtual_path.cmp(&b.virtual_path));
    Ok(json!(
        files
            .into_iter()
            .map(|file| { json!({"path": file.virtual_path, "size": file.size}) })
            .collect::<Vec<_>>()
    ))
}

fn supported_track(path: &str, size: u64, expanded: bool) -> bool {
    let extension = path.rsplit_once('.').map(|(_, extension)| extension);
    let flac = extension.is_some_and(|ext| ext.eq_ignore_ascii_case("flac"));
    let mp3 = extension.is_some_and(|ext| ext.eq_ignore_ascii_case("mp3"));
    size > 0 && if expanded {
        size <= i64::MAX as u64 && (flac || mp3)
    } else {
        size <= FIXTURE_MAX_BYTES && flac
    }
}

fn track_details(user: &str, path: &str, size: u64, attribute: impl Fn(u32) -> Option<u32>) -> Value {
    let mut track = json!({"user": user, "path": path, "size": size});
    for (key, code) in [("bitrate", 0), ("sampleRate", 4), ("bitDepth", 5)] {
        if let Some(value) = attribute(code).filter(|&value| value > 0) {
            track[key] = json!(value);
        }
    }
    track
}

fn search_tracks(results: Vec<SearchResult>, expanded: bool) -> Vec<Value> {
    results
        .into_iter()
        .flat_map(|result| result.files)
        .filter(|file| supported_track(&file.name, file.size, expanded))
        .take(if expanded { usize::MAX } else { 100 })
        .map(|file| track_details(&file.username, &file.name, file.size, |code| {
            if expanded { file.attribs.get(&code).copied() } else { None }
        }))
        .collect()
}

fn field<'a>(request: &'a Value, key: &str) -> Result<&'a str> {
    request[key]
        .as_str()
        .ok_or_else(|| format!("Missing {key}").into())
}

fn operate(request: Value) -> Result<Value> {
    let operation = field(&request, "operation")?;
    if operation == "sharePreview" {
        return share_preview(Path::new(field(&request, "root")?));
    }
    let mut session = SESSION
        .lock()
        .map_err(|_| "The fixture session stopped unexpectedly")?;
    match operation {
        "stop" => {
            if request["endSession"].as_bool() == Some(true) {
                session.credentials = None;
                let stopped = session.stop(true);
                session.client = None;
                session.wishlist = None;
                return stopped;
            }
            return session.stop(request["disconnect"].as_bool().unwrap_or(false));
        }
        "connect" => {
            return session.sign_in(field(&request, "username")?, field(&request, "password")?);
        }
        "poll" => return session.poll(),
        "shareStatus" => return Ok(session.share_status()),
        "shareOff" => return session.stop_sharing(),
        "shareOn" => return session.start_sharing(field(&request, "root")?.into()),
        "wishlistPoll" => return session.wishlist_poll(&request),
        "chatPoll" | "chatPrivate" | "chatJoin" | "chatLeave" | "chatRoom" => {
            return session.chat(operation, &request);
        }
        _ => {}
    }
    if session.download.is_some() {
        return Err("A fixture download is still active".into());
    }
    if operation == "download" {
        return session.start(&request);
    }
    session.client(request["diagnostic"].as_bool().unwrap_or(false))?;
    let session = &mut *session;
    let client = session.client.as_ref().expect("connected fixture client");
    let expanded = request["expanded"].as_bool().unwrap_or(false);
    match operation {
        "search" => {
            let query = field(&request, "query")?;
            if let Some(wishlist) = session.wishlist.as_mut() {
                wishlist.before_manual_search(client, query);
            }
            let results = client.search(query, Duration::from_secs(4))?;
            let _ = client.forget_search(query);
            Ok(json!(search_tracks(results, expanded)))
        }
        "folder" => {
            let user = field(&request, "user")?;
            let folder = field(&request, "folder")?;
            client.request_folder_contents(user, folder)?;
            let deadline = Instant::now() + Duration::from_secs(12);
            while Instant::now() < deadline {
                if let Some(dirs) = client.take_folder_contents(user, folder) {
                    let tracks: Vec<_> = dirs.into_iter().filter(|dir| dir.name == folder)
                        .flat_map(|dir| dir.files)
                        .filter(|file| supported_track(&file.name, file.size, expanded) && !file.name.contains(['/', '\\']))
                        .take(if expanded { usize::MAX } else { 100 })
                        .map(|file| {
                            let path = if folder.is_empty() { file.name.clone() } else { format!("{folder}\\{}", file.name) };
                            track_details(user, &path, file.size, |code| {
                                if expanded { file.attribute(code) } else { None }
                            })
                        })
                        .collect();
                    return Ok(json!(tracks));
                }
                thread::sleep(Duration::from_millis(100));
            }
            Err("The uploader did not return the folder listing".into())
        }
        _ => Err("Unknown fixture operation".into()),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_localmusic_player_SoulseekFixture_nativeRequest(
    mut env: JNIEnv<'_>,
    _object: JObject<'_>,
    request: JString<'_>,
) -> jstring {
    let response = catch_unwind(AssertUnwindSafe(|| -> Result<Value> {
        let input: String = env.get_string(&request)?.into();
        operate(serde_json::from_str(&input)?)
    }));
    let output = match response {
        Ok(Ok(value)) => json!({"result": value}),
        Ok(Err(error)) => json!({"error": error.to_string()}),
        Err(_) => json!({"error": "The fixture engine stopped unexpectedly"}),
    };
    env.new_string(output.to_string())
        .map_or(ptr::null_mut(), JString::into_raw)
}
