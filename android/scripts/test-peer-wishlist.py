"""Announce a wishlist interval and answer wishlist searches from Peer Test."""

from importlib import import_module
import socket
import struct
import time
import zlib


wire = import_module("test-peer-sharing")
UPLOADER = "peer-fixture-wishlist"
INTERVAL = 5
MATCHES = {"Amber": "Amber.flac", "Violet": "Violet.flac"}


def reply(token, name):
    path = wire.string(f"Wishlist Album\\{name}")
    file = b"\x01" + path + wire.pack("Q", 152312) + wire.string("") + wire.pack("I", 0)
    payload = wire.string(UPLOADER) + wire.pack("II", token, 1) + file + wire.pack("BII", 1, 0, 0)
    with wire.peer(UPLOADER, "127.0.0.1") as peer:
        wire.frame(peer, wire.pack("I", 9) + zlib.compress(payload))


def session(sock):
    last_wish = None
    while True:
        code, payload = wire.receive(sock)
        if code == 1:
            username, _ = wire.unpack_string(payload)
            assert username == wire.ACCOUNT, username
            wire.frame(sock, wire.pack("IB", 1, 1) + wire.string("Private wishlist test")
                       + socket.inet_aton("127.0.0.1") + wire.string("") + b"\0")
            wire.frame(sock, wire.pack("II", 104, INTERVAL))
            print(f"Online, announced {INTERVAL}s interval.", flush=True)
        elif code == 103:
            token, = struct.unpack_from("<I", payload)
            query, _ = wire.unpack_string(payload[4:])
            now = time.monotonic()
            elapsed = None if last_wish is None else now - last_wish
            assert elapsed is None or elapsed >= INTERVAL - 0.1, (query, elapsed)
            last_wish = now
            gap = "first request" if elapsed is None else f"{elapsed:.2f}s since previous"
            print(f"{now:.3f} Wishlist request: {query} ({gap})", flush=True)
            if query in MATCHES:
                reply(token, MATCHES[query])


if __name__ == "__main__":
    with wire.listener(45415) as server:
        print("Waiting for Peer Test on loopback port 45415.", flush=True)
        while True:
            sock, _ = server.accept()
            with sock:
                try:
                    session(sock)
                except (EOFError, ConnectionResetError):
                    print("Offline.", flush=True)
