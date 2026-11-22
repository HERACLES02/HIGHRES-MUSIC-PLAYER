use jni::JNIEnv;
use jni::objects::{JObject, JString};
use jni::sys::jstring;
use serde_json::{Value, json};
use soulseek_rs::shares::Shares;
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

struct Session {
    client: Option<Client>,
    download: Option<Transfer>,
    share: Option<PathBuf>,
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
    download: None,
    share: None,
});

impl Session {
    fn client(&mut self) -> Result<&Client> {
        if self
            .client
            .as_ref()
            .is_some_and(|client| client.session_loss().is_some())
        {
            self.stop_sharing()?;
            self.client = None;
        }
        if self.client.is_none() {
            self.client = Some(connect()?);
        }
        Ok(self.client.as_ref().expect("connected fixture client"))
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
        if !flac(basename, size) || basename.contains(['\0', ':']) {
            return Err("The selected track has an invalid name or size".into());
        }
        let (_, events) =
            self.client()?
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

fn connect() -> Result<Client> {
    let mut settings = ClientSettings::new("local-music-fixture", "local-fixture");
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

fn flac(path: &str, size: u64) -> bool {
    size > 0 && size <= FIXTURE_MAX_BYTES && path.to_ascii_lowercase().ends_with(".flac")
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
        "stop" => return session.stop(request["disconnect"].as_bool().unwrap_or(false)),
        "poll" => return session.poll(),
        "shareStatus" => return Ok(session.share_status()),
        "shareOff" => return session.stop_sharing(),
        "shareOn" => return session.start_sharing(field(&request, "root")?.into()),
        _ => {}
    }
    if session.download.is_some() {
        return Err("A fixture download is still active".into());
    }
    if operation == "download" {
        return session.start(&request);
    }
    let client = session.client()?;
    match operation {
        "connect" => Ok(Value::Null),
        "search" => {
            let results = client.search(field(&request, "query")?, Duration::from_secs(4))?;
            let tracks: Vec<_> = results
                .into_iter()
                .flat_map(|result| result.files)
                .filter(|file| flac(&file.name, file.size))
                .take(100)
                .map(|file| json!({"user": file.username, "path": file.name, "size": file.size}))
                .collect();
            Ok(json!(tracks))
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
                        .filter(|file| flac(&file.name, file.size) && !file.name.contains(['/', '\\']))
                        .take(100)
                        .map(|file| json!({"user": user, "path": format!("{folder}\\{}", file.name), "size": file.size}))
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
