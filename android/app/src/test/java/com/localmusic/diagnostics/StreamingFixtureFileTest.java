package com.localmusic.diagnostics;

import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

public final class StreamingFixtureFileTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public static void main(String[] args) throws Exception {
        byte[] data = new byte[123457];
        for (int i = 0; i < data.length; i++) data[i] = (byte)(i * 37 + 19);
        var output = new ByteArrayOutputStream();
        check(StreamingFixtureFile.copyVerified(new ByteArrayInputStream(data), output, hash(data)) == data.length, "Verified encoded byte count");
        check(Arrays.equals(data, output.toByteArray()), "Exact encoded bytes and final partial buffer");
        boolean rejected = false;
        try { StreamingFixtureFile.copyVerified(new ByteArrayInputStream(data), OutputStream.nullOutputStream(), "0".repeat(64)); }
        catch (IOException expected) { rejected = true; }
        check(rejected, "Corrupt asset hash blocks publication");
        rejected = false;
        try { StreamingFixtureFile.copyVerified(new ByteArrayInputStream(new byte[0]), OutputStream.nullOutputStream(), hash(new byte[0])); }
        catch (IOException expected) { rejected = true; }
        check(rejected, "Empty asset rejected");
        final int[] copied = {0};
        var counted = new OutputStream() {
            public void write(int b) { copied[0]++; }
            public void write(byte[] b, int offset, int count) { check(count <= 65536, "Bounded copy block"); copied[0] += count; }
        };
        rejected = false;
        try { StreamingFixtureFile.copyVerified(new ByteArrayInputStream(new byte[8*1024*1024+1]), counted, "0".repeat(64)); }
        catch (IOException expected) { rejected = true; }
        check(rejected && copied[0] <= 8*1024*1024, "Oversized copy rejected before excess output");
        copied[0] = 0;
        byte[] maximum = new byte[8*1024*1024];
        check(StreamingFixtureFile.copyVerified(new ByteArrayInputStream(maximum), counted, hash(maximum)) == maximum.length, "Exact encoded cap accepted");
        rejected = false;
        try { StreamingFixtureFile.copyVerified(new InputStream() { public int read() throws IOException { throw new IOException("EIO"); } }, counted, "0".repeat(64)); }
        catch (IOException expected) { rejected = true; }
        check(rejected, "Read failure propagates to caller cleanup");
        System.out.println("Streaming fixture copy checks passed.");
    }
}
