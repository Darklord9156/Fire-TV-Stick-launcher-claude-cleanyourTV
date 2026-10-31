package tv.rajra.liquidhome;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class WallpaperFeatureTests {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        testQrMatrix();
        File directory = new File(args[0]);
        if (!directory.exists() && !directory.mkdirs()) throw new AssertionError("Could not create test directory");
        for (File file : directory.listFiles()) file.delete();
        testUploadServer(directory);
        System.out.println("Wallpaper feature tests: " + assertions + " assertions passed.");
    }

    private static void testQrMatrix() {
        boolean[][] matrix = QrMatrix.encode("http://192.0.2.1:43210/upload/0123456789abcdef0123456789abcdef0123456789abcdef");
        check(matrix.length == 37, "QR is version 5 with the expected side length");
        for (boolean[] row : matrix) check(row.length == 37, "QR matrix is square");
        check(matrix[0][0], "finder pattern outer corner is dark");
        check(!matrix[1][1], "finder pattern inner ring is light");
        check(matrix[2][2], "finder pattern center ring is dark");
        check(!matrix[3][7], "finder separator is light");
        check(matrix[30][30] && !matrix[29][30] && matrix[28][30], "alignment pattern is present");
        int format = 0x77C4; // version 5, correction level L, mask 0
        for (int i = 0; i <= 5; i++) check(matrix[i][8] == bit(format, i), "first format copy bit " + i);
        check(matrix[7][8] == bit(format, 6) && matrix[8][8] == bit(format, 7) && matrix[8][7] == bit(format, 8), "first format copy middle bits");
        for (int i = 9; i < 15; i++) check(matrix[8][14 - i] == bit(format, i), "first format copy bit " + i);
        for (int i = 0; i < 8; i++) check(matrix[8][36 - i] == bit(format, i), "second format copy bit " + i);
        for (int i = 8; i < 15; i++) check(matrix[37 - 15 + i][8] == bit(format, i), "second format copy bit " + i);
        check(matrix[29][8], "QR dark module is present");
        boolean rejected = false;
        try { QrMatrix.encode(repeat('x', 101)); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "overlong QR payload is rejected");
    }

    private static void testUploadServer(File directory) throws Exception {
        CountDownLatch uploaded = new CountDownLatch(2);
        final String[] saved = new String[2];
        WallpaperTransferServer server = new WallpaperTransferServer(directory, new WallpaperTransferServer.Listener() {
            @Override public void onUpload(String filename, boolean success, String message) {
                if (success && saved[0] == null) saved[0] = filename;
                else if (success) saved[1] = filename;
                uploaded.countDown();
            }
            @Override public void onServerStopped(String message) { }
        }, InetAddress.getByName("127.0.0.1"));
        server.start();
        String route = "/upload/" + server.getToken();
        String root = "http://127.0.0.1:" + server.getPort();
        HttpResult page = request("GET", root + route, null, null);
        check(page.status == 200 && page.body.contains("image/webp") && page.body.contains("onprogress"), "authenticated upload page advertises formats and progress");
        HttpResult invalid = request("GET", root + "/upload/wrong-token", null, null);
        check(invalid.status == 404, "invalid temporary token is rejected");

        byte[] png = new byte[] {(byte)0x89,'P','N','G',13,10,26,10,0,0,0,0};
        HttpResult first = upload(root + route, "folder\\photo.png", "image/png", png);
        check(first.status == 200 && first.body.contains("photo.png"), "PNG image uploads with success response");
        HttpResult duplicate = upload(root + route, "photo.png", "image/png", png);
        check(duplicate.status == 200 && duplicate.body.contains("photo_2.png"), "filename collisions are made unique");
        HttpResult wrongType = upload(root + route, "photo.gif", "image/gif", png);
        check(wrongType.status == 415, "unsupported extension is rejected");
        HttpResult wrongSignature = upload(root + route, "photo.jpg", "image/jpeg", new byte[] {1,2,3,4});
        check(wrongSignature.status == 415, "file content must match its image extension");
        check(uploaded.await(2, TimeUnit.SECONDS), "server reports successful uploads");
        check("photo.png".equals(saved[0]) && "photo_2.png".equals(saved[1]), "server reports saved filenames");
        check(Arrays.equals(png, readAll(new File(directory, "photo.png"))), "uploaded data is persisted byte-for-byte");
        server.close();
        for (int i = 0; i < 20 && server.isRunning(); i++) Thread.sleep(25);
        check(!server.isRunning(), "closing a transfer session stops its server");
    }

    private static HttpResult upload(String url, String filename, String mime, byte[] image) throws Exception {
        String boundary = "----LiquidHomeTestBoundary";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"" + filename + "\"\r\nContent-Type: " + mime + "\r\n\r\n");
        body.write(image);
        write(body, "\r\n--" + boundary + "--\r\n");
        return request("POST", url, "multipart/form-data; boundary=" + boundary, body.toByteArray());
    }

    private static HttpResult request(String method, String url, String contentType, byte[] body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String response = stream == null ? "" : new String(readAll(stream), StandardCharsets.UTF_8);
        connection.disconnect();
        return new HttpResult(status, response);
    }

    private static byte[] readAll(File file) throws Exception { try (FileInputStream in = new FileInputStream(file)) { return readAll(in); } }
    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = source.read(buffer)) >= 0) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }
    private static void write(ByteArrayOutputStream out, String text) throws Exception { out.write(text.getBytes(StandardCharsets.US_ASCII)); }
    private static String repeat(char value, int count) { char[] chars = new char[count]; Arrays.fill(chars, value); return new String(chars); }
    private static boolean bit(int value, int index) { return ((value >>> index) & 1) != 0; }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }

    private static final class HttpResult {
        final int status; final String body;
        HttpResult(int status, String body) { this.status = status; this.body = body; }
    }
}
