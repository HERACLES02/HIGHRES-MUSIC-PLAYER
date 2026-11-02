package com.localmusic.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Hashes encoded audio plus decoding configuration, excluding editable tag areas. Fails closed. */
public final class AudioPayloadDigest {
    private final RandomAccessFile file;
    private final MessageDigest digest;
    private long audioBytes;
    private AudioPayloadDigest(File source) throws IOException {
        file = new RandomAccessFile(source, "r");
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static String hash(File source) throws IOException {
        AudioPayloadDigest p = new AudioPayloadDigest(source);
        try {
            long start = p.skipId3(); String signature = p.text(start, 4);
            if (signature.equals("fLaC")) p.flac(start);
            else if (signature.equals("OggS")) p.ogg(start);
            else if (p.file.length() >= 12 && p.text(4, 4).equals("ftyp")) p.mp4(0, p.file.length(), 0);
            else {
                p.file.seek(start);
                if (p.file.readUnsignedByte() != 255 || (p.file.readUnsignedByte() & 224) != 224) throw new IOException("Unsupported audio payload container");
                long end = p.file.length();
                if (end >= 128 && p.text(end - 128, 3).equals("TAG")) end -= 128;
                if (end >= 32 && p.text(end - 32, 8).equals("APETAGEX")) {
                    p.file.seek(end - 20); long size = p.le32(); p.file.seek(end - 12); long flags = p.le32();
                    if (size < 32 || size > end - start) throw new IOException("Invalid APE size");
                    end -= size; if ((flags & 0x80000000L) != 0) end -= 32;
                }
                p.range(start, end - start, true);
            }
            if (p.audioBytes <= 0) throw new IOException("No encoded audio payload");
            StringBuilder out = new StringBuilder(); for (byte b : p.digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } finally { p.file.close(); }
    }
    private String text(long at, int count) throws IOException { file.seek(at); byte[] b = new byte[count]; file.readFully(b); return new String(b, StandardCharsets.ISO_8859_1); }
    private long le32() throws IOException { return Integer.toUnsignedLong(Integer.reverseBytes(file.readInt())); }
    private long skipId3() throws IOException {
        if (file.length() < 4 || !text(0, 3).equals("ID3")) return 0;
        file.seek(3); int version = file.readUnsignedByte(); file.readUnsignedByte(); int flags = file.readUnsignedByte();
        if (version < 2 || version > 4) throw new IOException("Unsupported ID3 version");
        long size = 0; for (int i = 0; i < 4; i++) { int b = file.readUnsignedByte(); if (b >= 128) throw new IOException("Bad synchsafe size"); size = (size << 7) | b; }
        long end = 10 + size + ((version == 4 && (flags & 16) != 0) ? 10 : 0);
        if (end >= file.length()) throw new IOException("ID3 exceeds source"); return end;
    }
    private void range(long offset, long length, boolean audio) throws IOException {
        if (offset < 0 || length < 0 || offset > file.length() || length > file.length() - offset) throw new IOException("Payload range exceeds source");
        lengthPrefix(length); file.seek(offset); byte[] buffer = new byte[32768]; long remaining = length;
        while (remaining > 0) { int n = file.read(buffer, 0, (int)Math.min(buffer.length, remaining)); if (n < 0) throw new EOFException(); digest.update(buffer, 0, n); remaining -= n; }
        if (audio) audioBytes += length;
    }
    private void lengthPrefix(long length) { for (int i = 7; i >= 0; i--) digest.update((byte)(length >>> (i * 8))); }
    private void flac(long start) throws IOException {
        long at = start + 4; boolean last = false; boolean streamInfo = false;
        for (int count = 0; !last; count++) {
            if (count > 100000) throw new IOException("Too many FLAC blocks");
            file.seek(at); int type = file.readUnsignedByte(); last = (type & 128) != 0; type &= 127;
            int size = (file.readUnsignedByte() << 16) | (file.readUnsignedByte() << 8) | file.readUnsignedByte(); at += 4;
            if (at + size > file.length()) throw new IOException("Invalid FLAC block");
            if (type == 0) { if (streamInfo || size != 34) throw new IOException("Invalid FLAC stream info"); range(at, size, false); streamInfo = true; }
            at += size;
        }
        if (!streamInfo) throw new IOException("Missing FLAC stream info");
        long end = file.length(); if (end >= 128 && text(end - 128, 3).equals("TAG")) end -= 128;
        range(at, end - at, true);
    }
    private void mp4(long begin, long end, int depth) throws IOException {
        if (depth > 10) throw new IOException("Excessive MP4 nesting");
        long at = begin;
        while (at < end) {
            if (end - at < 8) throw new IOException("Truncated MP4 atom");
            file.seek(at); long size = Integer.toUnsignedLong(file.readInt()); String name = text(at + 4, 4); int header = 8;
            if (size == 1) { file.seek(at + 8); size = file.readLong(); header = 16; }
            if (size == 0) size = end - at;
            if (size < header || size > end - at) throw new IOException("Invalid MP4 atom range");
            if (name.equals("mdat")) range(at + header, size - header, true);
            if (name.equals("stsd")) range(at + header, size - header, false);
            if (Set.of("moov", "trak", "mdia", "minf", "stbl").contains(name)) mp4(at + header, at + size, depth + 1);
            at += size;
        }
    }
    private static final class Stream {
        int packet, headers, sequence; boolean initialized; long granule;
        final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    }
    private void ogg(long start) throws IOException {
        Map<Long, Stream> streams = new LinkedHashMap<>(); long at = start;
        while (at < file.length()) {
            if (!text(at, 4).equals("OggS")) throw new IOException("Invalid Ogg page");
            file.seek(at + 4); if (file.readUnsignedByte() != 0) throw new IOException("Unsupported Ogg version");
            int flags = file.readUnsignedByte(); long granule = Long.reverseBytes(file.readLong()); long serial = le32(); long sequence = le32();
            file.skipBytes(4); int count = file.readUnsignedByte(); byte[] laces = new byte[count]; file.readFully(laces);
            Stream stream = streams.computeIfAbsent(serial, ignored -> new Stream());
            if (streams.size() > 100) throw new IOException("Too many Ogg streams");
            if (stream.initialized && sequence != Integer.toUnsignedLong(stream.sequence + 1)) throw new IOException("Ogg sequence gap");
            if ((flags & 1) != 0 && stream.pending.size() == 0) throw new IOException("Missing Ogg continuation");
            stream.initialized = true; stream.sequence = (int)sequence; if (granule >= 0) stream.granule = granule;
            for (byte lace : laces) {
                int size = lace & 255; byte[] segment = new byte[size]; file.readFully(segment); stream.pending.write(segment);
                if (stream.pending.size() > 16 * 1024 * 1024) throw new IOException("Oversized Ogg packet");
                if (size < 255) {
                    byte[] packet = stream.pending.toByteArray(); stream.pending.reset();
                    if (stream.packet == 0) {
                        String magic = new String(packet, 0, Math.min(8, packet.length), StandardCharsets.ISO_8859_1);
                        stream.headers = magic.startsWith("OpusHead") ? 2 : magic.startsWith("\u0001vorbis") ? 3 : 0;
                        if (stream.headers == 0) throw new IOException("Unsupported Ogg codec");
                    }
                    if (stream.packet != 1) { lengthPrefix(packet.length); digest.update(packet); if (stream.packet >= stream.headers) audioBytes += packet.length; }
                    stream.packet++;
                }
            }
            at = file.getFilePointer();
        }
        for (Stream stream : streams.values()) { if (stream.pending.size() != 0 || stream.packet <= stream.headers) throw new IOException("Incomplete Ogg stream"); lengthPrefix(stream.granule); }
    }
    public static void main(String[] args) throws IOException { for (String path : args) System.out.println(hash(new File(path))); }
}
