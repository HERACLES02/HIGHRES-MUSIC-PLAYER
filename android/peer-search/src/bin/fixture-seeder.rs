use soulseek_rs::{Client, ClientSettings, PeerAddress};
use std::{env, error::Error, fs, thread, time::Duration};

fn main() -> Result<(), Box<dyn Error>> {
    let args: Vec<_> = env::args().skip(1).collect();
    let [directory, username, port] = args.as_slice() else {
        return Err("Usage: fixture-seeder <generated-fixture-directory> <uploader> <port>".into());
    };
    let mut settings = ClientSettings::new(username, "local-fixture");
    settings.server_address = PeerAddress::new("127.0.0.1".into(), 45415);
    settings.shared_directories = vec![fs::canonicalize(directory)?.display().to_string()];
    settings.listen_port = port.parse()?;
    let mut client = Client::with_settings(settings);
    client.connect()?;
    if !client.login()? {
        return Err("Private fixture login was declined".into());
    }
    println!("{username} serving {}", client.shared_counts().1);
    while client.session_loss().is_none() {
        thread::sleep(Duration::from_millis(300));
    }
    Err("Private fixture session ended".into())
}
