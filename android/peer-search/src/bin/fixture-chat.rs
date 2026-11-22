use soulseek_rs::types::RoomEvent;
use soulseek_rs::{Client, ClientSettings, PeerAddress};
use std::io;
use std::thread;
use std::time::{Duration, Instant};

const PHONE: &str = "local-music-fixture";
const PEER: &str = "peer-fixture-chat";
const ROOM: &str = "fixture-chat";

fn wait_for(mut received: impl FnMut() -> bool, description: &str) {
    let deadline = Instant::now() + Duration::from_secs(30);
    while Instant::now() < deadline {
        if received() {
            return;
        }
        thread::sleep(Duration::from_millis(100));
    }
    panic!("Timed out waiting for {description}");
}

fn confirm(prompt: &str) -> io::Result<()> {
    println!("{prompt} Then press Enter here.");
    let mut input = String::new();
    if io::stdin().read_line(&mut input)? == 0 {
        return Err(io::ErrorKind::UnexpectedEof.into());
    }
    Ok(())
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut settings = ClientSettings::new(PEER, "local-fixture");
    settings.server_address = PeerAddress::new("127.0.0.1".into(), 45415);
    settings.listen_port = 45419;
    let mut peer = Client::with_settings(settings);
    peer.connect()?;
    if !peer.login()? {
        return Err("Fixture chat login declined".into());
    }
    confirm("Go online in Soulseek and stay on Search.")?;
    peer.send_private_message(PHONE, "hello from fixture peer")?;
    println!("Open Chat, check the unread badge, and send: private reply");
    wait_for(
        || {
            peer.take_private_messages()
                .iter()
                .any(|msg| msg.username() == PHONE && msg.message() == "private reply")
        },
        "the phone's private reply",
    );
    println!("Received private reply from the phone.");

    peer.join_room(ROOM)?;
    confirm("Open Room and join fixture-chat. After joining, switch to Search or lock the phone.")?;
    peer.say_in_room(ROOM, "hello from fixture room")?;
    println!("Open Chat → Room, check the unread badge, and send: room reply");
    wait_for(
        || {
            peer.take_room_events().iter().any(|event| {
                matches!(event, RoomEvent::Message { room, username, message }
                    if room == ROOM && username == PHONE && message == "room reply")
            })
        },
        "the phone's room reply",
    );
    println!("Received room reply from the phone.");
    println!("Reconnect and reopen peer-fixture-chat. Check that both private messages reappear.");
    Ok(())
}
