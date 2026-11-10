package com.localmusic.diagnostics;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Bounded copy of an encoded bundled fixture; caller owns both streams. */
public final class StreamingFixtureFile {
    public static long copyVerified(InputStream input, OutputStream output, String sha256) throws IOException {
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        byte[] buffer = new byte[65536];
        long total = 0;
        for (;;) {
            int count = input.read(buffer);
            if (count < 0) break;
            if (count == 0 || total > 8*1024*1024L-count) throw new IOException("Encoded fixture size/read rejected");
            digest.update(buffer, 0, count);
            output.write(buffer, 0, count);
            total += count;
        }
        StringBuilder hash = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hash.append("0123456789abcdef".charAt((b & 255) >> 4));
            hash.append("0123456789abcdef".charAt(b & 15));
        }
        if (total == 0 || !hash.toString().equals(sha256)) throw new IOException("Encoded fixture checksum/size failed");
        return total;
    }
}
