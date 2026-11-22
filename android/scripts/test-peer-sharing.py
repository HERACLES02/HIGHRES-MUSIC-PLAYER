"""Check a private Peer Test session against a loopback coordinator and fixture peers."""

import argparse
from contextlib import ExitStack
from functools import partial
import hashlib
import ipaddress
import socket
import struct
import threading
import zlib
from pathlib import Path


ACCOUNT = "local-music-fixture"
PRIVILEGED = ("priority-first", "priority-second")


def pack(fmt, *values):
    return struct.pack("<" + fmt, *values)


def string(value):
    data = value.encode()
    return pack("I", len(data)) + data


def exact(sock, size):
    data = bytearray()
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise EOFError("Fixture socket closed")
        data.extend(chunk)
    return bytes(data)


def frame(sock, payload):
    sock.sendall(pack("I", len(payload)) + payload)


def receive(sock, fmt="I"):
    size, = struct.unpack("<I", exact(sock, 4))
    assert 1 <= size <= 1024 * 1024, size
    payload = exact(sock, size)
    return struct.unpack_from("<" + fmt, payload)[0], payload[struct.calcsize(fmt):]


def unpack_string(data):
    size, = struct.unpack_from("<I", data)
    return data[4:4 + size].decode(), data[4 + size:]


def listener(port, host="127.0.0.1"):
    sock = socket.socket()
    sock.bind((host, port))
    sock.listen()
    return sock


def coordinator(server, file_host):
    while True:
        sock, _ = server.accept()
        with sock:
            try:
                while True:
                    code, payload = receive(sock)
                    if code == 1:
                        username, _ = unpack_string(payload)
                        assert username == ACCOUNT, username
                        frame(sock, pack("IB", 1, 1) + string("Private upload test")
                              + socket.inet_aton("127.0.0.1") + string("") + b"\0")
                        frame(sock, pack("II", 69, len(PRIVILEGED)) + b"".join(map(string, PRIVILEGED)))
                    elif code == 3:
                        username, _ = unpack_string(payload)
                        address = socket.inet_aton(file_host)[::-1]
                        frame(sock, pack("I", 3) + string(username) + address + pack("III", 45418, 0, 0))
                    elif code == 92:
                        frame(sock, pack("II", 92, 86400))
            except (EOFError, ConnectionResetError):
                pass


def peer(username, phone_host):
    sock = socket.create_connection((phone_host, 45417), timeout=8)
    frame(sock, b"\x01" + string(username) + string("P") + pack("I", 0))
    return sock


def browse(username, connect):
    with connect(username) as sock:
        frame(sock, pack("I", 4))
        code, data = receive(sock)
        assert code == 5, code
        data = zlib.decompress(data)
        count, = struct.unpack_from("<I", data)
        data = data[4:]
        listing = {}
        for _ in range(count):
            directory, data = unpack_string(data)
            files, = struct.unpack_from("<I", data)
            data = data[4:]
            for _ in range(files):
                name, data = unpack_string(data[1:])
                size, = struct.unpack_from("<Q", data)
                _, data = unpack_string(data[8:])
                attributes, = struct.unpack_from("<I", data)
                data = data[4 + attributes * 8:]
                listing[directory + "\\" + name] = size
        return listing


def queue(sock, path):
    frame(sock, pack("I", 43) + string(path))


def place(sock, path):
    frame(sock, pack("I", 51) + string(path))
    code, data = receive(sock)
    assert code == 44, code
    name, data = unpack_string(data)
    assert name == path, name
    return struct.unpack("<I", data)[0]


def accept_upload(control, file_listener, path, size):
    code, data = receive(control)
    assert code == 40, code
    direction, token = struct.unpack_from("<II", data)
    name, data = unpack_string(data[8:])
    assert (direction, name, struct.unpack("<Q", data)[0]) == (1, path, size)
    frame(control, pack("IIB", 41, token, 1))
    sock, _ = file_listener.accept()
    sock.settimeout(8)
    try:
        code, init = receive(sock, "B")
        assert code == 1 and unpack_string(init)[0] == ACCOUNT
        assert exact(sock, 4) == pack("I", token)
        return sock
    except BaseException:
        sock.close()
        raise


def finish(sock, size, digest):
    with sock:
        sock.sendall(pack("Q", 0))
        assert hashlib.sha256(exact(sock, size)).digest() == digest
        assert sock.recv(1) == b""


def private_address(value):
    address = ipaddress.IPv4Address(value)
    networks = ("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
    if not any(address in ipaddress.IPv4Network(network) for network in networks):
        raise argparse.ArgumentTypeError("Use loopback or private Wi-Fi fixture addresses")
    return str(address)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture_directory", type=Path)
    parser.add_argument("--phone-host", type=private_address, default="127.0.0.1")
    parser.add_argument("--file-host", type=private_address, default="127.0.0.1")
    args = parser.parse_args()
    root = args.fixture_directory
    files = {str(root.name + "\\" + "\\".join(path.relative_to(root).parts)): path
             for path in sorted(root.rglob("*.flac"))}
    assert any("\\" in path[len(root.name) + 1:] for path in files), "Include a nested FLAC"
    expected = {name: path.stat().st_size for name, path in files.items()}
    path = min(expected, key=expected.get)
    size, digest = expected[path], hashlib.sha256(files[path].read_bytes()).digest()
    connect = partial(peer, phone_host=args.phone_host)
    with listener(45415) as server, listener(45418, args.file_host) as file_listener:
        threading.Thread(target=coordinator, args=(server, args.file_host), daemon=True).start()
        file_listener.settimeout(8)
        input("Go online in Peer Test. Leave sharing off, then press Enter. ")
        assert browse("initial-browser", connect) == {}, "Sharing must start off"
        input("Confirm the 86400-second account status, preview this folder, and enable sharing. Press Enter. ")
        assert browse("active-browser", connect) == expected, "Peer listing must match the preview"
        with ExitStack() as stack:
            active = stack.enter_context(connect("active"))
            queue(active, path)
            held = stack.enter_context(accept_upload(active, file_listener, path, size))
            waiters = [stack.enter_context(connect(name))
                       for name in ("regular-first", "regular-second", *PRIVILEGED)]
            for waiter, position in zip(waiters, (1, 2, 1, 2), strict=True):
                queue(waiter, path)
                assert place(waiter, path) == position
            assert [place(waiter, path) for waiter in waiters] == [3, 4, 1, 2]
            print("Actual waiting order: both privileged peers first, FIFO within both groups.", flush=True)
            input("Lock the phone, then press Enter to let the active transfer finish. ")
            finish(held, size, digest)
            for waiter in waiters[2:] + waiters[:2]:
                finish(accept_upload(waiter, file_listener, path, size), size, digest)
            print("The active transfer finished first. All five uploads matched SHA-256.", flush=True)
        with connect("cancelled") as control:
            queue(control, path)
            with accept_upload(control, file_listener, path, size) as held, connect("waiting-at-stop") as waiting:
                queue(waiting, path)
                assert place(waiting, path) == 1
                input("Unlock the phone and turn sharing off, then press Enter. ")
                assert browse("final-browser", connect) == {}, "The listing must be withdrawn"
                held.sendall(pack("Q", 0))
                assert held.recv(1) == b"", "The active upload sent data after sharing was stopped"
                with connect("after-stop-request") as fresh:
                    queue(fresh, path)
                    assert place(fresh, path) == 0, "A new request for the withdrawn file must stay out of the queue"
        print("Sharing off withdrew the listing, stopped the active upload, and refused a new request.")


if __name__ == "__main__":
    main()
