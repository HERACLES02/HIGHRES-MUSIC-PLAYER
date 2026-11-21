"""Serve one synthetic FLAC using direct Soulseek P and F connections."""

import argparse
import hashlib
import ipaddress
from pathlib import Path
import secrets
import socket
import struct


REMOTE_PATH = r"Fixture\peer-tone.flac"
CONTROL_PORT = 45416
FILE_PORT = 45417
MAX_BYTES = 64 * 1024 * 1024


def pack(fmt, *values):
    return struct.pack("<" + fmt, *values)


def string(value):
    value = value.encode()
    return pack("I", len(value)) + value


def send_frame(sock, payload):
    sock.sendall(pack("I", len(payload)) + payload)


def exact(sock, size):
    result = b""
    while len(result) < size:
        chunk = sock.recv(size - len(result))
        if not chunk:
            raise EOFError("Peer closed the connection")
        result += chunk
    return result


def receive_frame(sock):
    size, = struct.unpack("<I", exact(sock, 4))
    if not 1 <= size <= 4096:
        raise ValueError("Unexpected Soulseek frame length")
    return exact(sock, size)


def serve(control, address, fixture, truncate):
    if receive_frame(control) != b"\x01" + string("local-music-fixture") + string("P") + pack("I", 0):
        raise ValueError("Unexpected control PeerInit")
    if receive_frame(control) != pack("I", 43) + string(REMOTE_PATH):
        raise ValueError("Only the test fixture is available")
    token = secrets.randbits(32)
    send_frame(control, pack("III", 40, 1, token) + string(REMOTE_PATH) + pack("Q", len(fixture)))
    if receive_frame(control) != pack("IIB", 41, token, 1):
        raise ValueError("Transfer was rejected")
    source = (control.getsockname()[0], 0)
    with socket.create_connection((address, FILE_PORT), timeout=10, source_address=source) as data:
        send_frame(data, b"\x01" + string("fixture-uploader") + string("F") + pack("I", 0))
        data.sendall(pack("I", token))
        offset, = struct.unpack("<Q", exact(data, 8))
        if offset > len(fixture):
            raise ValueError("Invalid requested offset")
        end = len(fixture) // 2 if truncate else len(fixture)
        data.sendall(fixture[offset:end])
        if not truncate and data.recv(1):
            raise ValueError("Unexpected data from downloader")
        print(f"Sent {end - offset}/{len(fixture)} bytes from offset {offset}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture", type=Path)
    parser.add_argument("--bind", default="127.0.0.1", type=ipaddress.IPv4Address)
    parser.add_argument("--truncate", action="store_true", help="Disconnect after half the fixture")
    args = parser.parse_args()
    lan = ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
    if not (args.bind == ipaddress.IPv4Address("127.0.0.1") or
            any(args.bind in ipaddress.IPv4Network(net) for net in lan)):
        parser.error("Bind to loopback or a private IPv4 address")
    with args.fixture.open("rb") as source:
        fixture = source.read(MAX_BYTES + 1)
    if not fixture.startswith(b"fLaC") or len(fixture) > MAX_BYTES:
        parser.error("Provide a generated FLAC up to 64 MiB")
    with socket.socket() as server:
        server.bind((str(args.bind), CONTROL_PORT))
        server.listen()
        print(f"Listening on {args.bind}:{CONTROL_PORT}", flush=True)
        print(f"Fixture SHA-256: {hashlib.sha256(fixture).hexdigest()}", flush=True)
        while True:
            control, (address, _) = server.accept()
            with control:
                control.settimeout(10)
                try:
                    serve(control, address, fixture, args.truncate)
                except (OSError, EOFError, ValueError) as error:
                    print(f"Transfer stopped: {error}", flush=True)


if __name__ == "__main__":
    main()
