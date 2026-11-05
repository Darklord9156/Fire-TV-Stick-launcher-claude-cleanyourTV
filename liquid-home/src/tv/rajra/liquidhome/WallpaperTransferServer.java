package tv.rajra.liquidhome;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Temporary, token-protected, dependency-free local HTTP image uploader. */
final class WallpaperTransferServer implements AutoCloseable {
    static final long MAX_UPLOAD_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_REQUEST_BYTES = MAX_UPLOAD_BYTES + 65536L;
    private static final long IDLE_TIMEOUT_MS = 15L * 60L * 1000L;
    private static final long SESSION_TIMEOUT_MS = 2L * 60L * 60L * 1000L;
    private static final int HEADER_LIMIT = 16384;
    private static final Pattern FILENAME = Pattern.compile("filename=\"((?:\\\\.|[^\"])*)\"");

    interface Listener {
        void onUpload(String filename, boolean success, String message);
        void onServerStopped(String message);
    }

    interface ImageValidator {
        boolean isDecodable(File file, String filename);
    }

    private final File wallpaperDirectory;
    private final Listener listener;
    private final InetAddress bindAddress;
    private final ImageValidator imageValidator;
    private final String token = createToken();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerSocket serverSocket;
    private volatile Socket currentClient;
    private volatile Thread serverThread;
    private volatile long startedAt;
    private volatile long lastActivityAt;
    private volatile String stopReason = "Transfer session stopped.";

    WallpaperTransferServer(File wallpaperDirectory, Listener listener) {
        this(wallpaperDirectory, listener, null, null);
    }

    WallpaperTransferServer(File wallpaperDirectory, Listener listener, InetAddress bindAddress) {
        this(wallpaperDirectory, listener, bindAddress, null);
    }

    WallpaperTransferServer(File wallpaperDirectory, Listener listener, InetAddress bindAddress, ImageValidator imageValidator) {
        this.wallpaperDirectory = wallpaperDirectory;
        this.listener = listener;
        this.bindAddress = bindAddress;
        this.imageValidator = imageValidator;
    }

    void start() throws IOException {
        if (!wallpaperDirectory.isDirectory() && !wallpaperDirectory.mkdirs()) throw new IOException("Wallpaper storage is unavailable.");
        ServerSocket socket = new ServerSocket(0, 8, bindAddress);
        socket.setSoTimeout(1000);
        serverSocket = socket;
        startedAt = lastActivityAt = System.currentTimeMillis();
        running.set(true);
        serverThread = new Thread(this::serve, "liquid-home-wallpaper-upload");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    boolean isRunning() { return running.get(); }

    String getToken() { return token; }

    int getPort() { return serverSocket == null ? -1 : serverSocket.getLocalPort(); }

    static String findLanAddress() throws IOException {
        List<InetAddress> addresses = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) throw new IOException("No network interfaces are available.");
        while (interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            try { if (!network.isUp() || network.isLoopback() || network.isVirtual()) continue; }
            catch (Exception ignored) { continue; }
            Enumeration<InetAddress> found = network.getInetAddresses();
            while (found.hasMoreElements()) {
                InetAddress address = found.nextElement();
                if (!(address instanceof Inet4Address) || address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
                if (isPrivateIpv4(address)) {
                    if (network.getName().toLowerCase(Locale.ROOT).matches(".*(wlan|wifi|eth|en).*")) return address.getHostAddress();
                    addresses.add(address);
                }
            }
        }
        if (!addresses.isEmpty()) return addresses.get(0).getHostAddress();
        throw new IOException("Connect the Fire TV to Wi-Fi and try again.");
    }

    private static boolean isPrivateIpv4(InetAddress address) {
        byte[] b = address.getAddress();
        int first = b[0] & 255, second = b[1] & 255;
        return first == 10 || first == 192 && second == 168 || first == 172 && second >= 16 && second <= 31;
    }

    private void serve() {
        String reason = "Transfer session stopped.";
        try {
            while (running.get()) {
                long now = System.currentTimeMillis();
                if (now - lastActivityAt > IDLE_TIMEOUT_MS || now - startedAt > SESSION_TIMEOUT_MS) {
                    reason = "Transfer session expired. Start a new session to upload more photos.";
                    break;
                }
                try (Socket client = serverSocket.accept()) {
                    currentClient = client;
                    client.setSoTimeout(30000);
                    handle(client);
                    lastActivityAt = System.currentTimeMillis();
                } catch (SocketTimeoutException ignored) {
                } catch (IOException e) {
                    if (running.get()) notifyUpload("", false, safeMessage(e));
                } finally {
                    currentClient = null;
                }
            }
        } catch (Exception e) {
            if (running.get()) reason = "Upload server stopped: " + safeMessage(e);
        } finally {
            running.set(false);
            closeSocket();
            stopReason = reason;
            if (listener != null) listener.onServerStopped(reason);
        }
    }

    private void handle(Socket socket) throws IOException {
        BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
        BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
        String request = readLine(input, 4096);
        if (request == null) return;
        String[] requestParts = request.split(" ", 3);
        if (requestParts.length != 3 || !requestParts[2].startsWith("HTTP/1.")) { respond(output, 400, "Bad request.", "text/plain; charset=utf-8"); return; }
        Map<String, String> headers = readHeaders(input);
        String method = requestParts[0];
        String path = requestParts[1];
        String route = "/upload/" + token;
        if ("GET".equals(method) && route.equals(path)) {
            respond(output, 200, uploadPage(), "text/html; charset=utf-8");
        } else if ("POST".equals(method) && constantTimeEquals(route, path)) {
            handleUpload(input, output, headers);
        } else if ("GET".equals(method) && "/".equals(path)) {
            respond(output, 302, "", "text/plain; charset=utf-8", "Location: " + route + "\r\n");
        } else {
            respond(output, 404, "This temporary upload link is invalid or expired.", "text/plain; charset=utf-8");
        }
    }

    private void handleUpload(InputStream input, OutputStream output, Map<String, String> headers) throws IOException {
        String contentType = headers.get("content-type");
        String lengthText = headers.get("content-length");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data;")) {
            respond(output, 415, "Choose a JPG, PNG or WebP image.", "text/plain; charset=utf-8"); return;
        }
        if (lengthText == null) { respond(output, 411, "The browser did not provide an upload length.", "text/plain; charset=utf-8"); return; }
        long contentLength;
        try { contentLength = Long.parseLong(lengthText); }
        catch (NumberFormatException e) { respond(output, 400, "Invalid upload length.", "text/plain; charset=utf-8"); return; }
        if (contentLength < 1 || contentLength > MAX_REQUEST_BYTES) { respond(output, 413, "Images must be smaller than 64 MB.", "text/plain; charset=utf-8"); return; }
        String boundary = boundaryFrom(contentType);
        if (boundary == null || boundary.length() > 100) { respond(output, 400, "Invalid upload boundary.", "text/plain; charset=utf-8"); return; }
        LimitedInputStream body = new LimitedInputStream(input, contentLength);
        String first = readLine(body, 256);
        if (!("--" + boundary).equals(first)) { respond(output, 400, "Invalid upload body.", "text/plain; charset=utf-8"); return; }
        Map<String, String> partHeaders = readHeaders(body);
        String disposition = partHeaders.get("content-disposition");
        if (disposition == null || !disposition.contains("name=\"image\"")) { respond(output, 400, "No image was attached.", "text/plain; charset=utf-8"); return; }
        Matcher filenameMatcher = FILENAME.matcher(disposition);
        if (!filenameMatcher.find()) { respond(output, 400, "The selected file has no name.", "text/plain; charset=utf-8"); return; }
        String originalName = filenameMatcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
        String safeName = safeFilename(originalName);
        if (safeName == null) { respond(output, 415, "Supported formats are JPG, JPEG, PNG and WebP.", "text/plain; charset=utf-8"); return; }

        File temporary = new File(wallpaperDirectory, ".upload-" + UUID.randomUUID().toString() + ".tmp");
        long written = 0;
        // Buffered writes avoid a native file write for every byte in the image.
        try (BufferedOutputStream file = new BufferedOutputStream(new FileOutputStream(temporary), 64 * 1024)) {
            written = copyUntilBoundary(body, file, ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII), MAX_UPLOAD_BYTES);
            // Consume the closing multipart marker and any remaining request bytes before
            // replying. Closing a TCP socket with unread request data can reset the
            // connection, which makes mobile browsers report a network error at 100%.
            drain(body);
        } catch (IOException e) {
            temporary.delete();
            respond(output, e instanceof UploadTooLargeException ? 413 : 400,
                    e instanceof UploadTooLargeException ? "Images must be smaller than 64 MB." : "The upload was incomplete.", "text/plain; charset=utf-8");
            return;
        }
        if (written == 0 || !hasValidImageSignature(temporary, safeName)
                || imageValidator != null && !imageValidator.isDecodable(temporary, safeName)) {
            temporary.delete();
            respond(output, 415, "The file extension and image data must match a supported image format.", "text/plain; charset=utf-8");
            return;
        }
        File target = uniqueTarget(safeName);
        if (!temporary.renameTo(target)) {
            temporary.delete();
            respond(output, 500, "Could not save the image to Fire TV storage.", "text/plain; charset=utf-8");
            notifyUpload(safeName, false, "Could not save image.");
            return;
        }
        respond(output, 200, "{\"ok\":true,\"filename\":\"" + jsonEscape(target.getName()) + "\"}", "application/json; charset=utf-8");
        notifyUpload(target.getName(), true, "Image uploaded and saved.");
    }

    private File uniqueTarget(String name) {
        File target = new File(wallpaperDirectory, name);
        if (!target.exists()) return target;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int index = 2;
        do { target = new File(wallpaperDirectory, stem + "_" + index++ + ext); } while (target.exists());
        return target;
    }

    private static String safeFilename(String original) {
        String leaf = original == null ? "" : original.replace('\\', '/');
        int slash = leaf.lastIndexOf('/');
        if (slash >= 0) leaf = leaf.substring(slash + 1);
        leaf = leaf.replaceAll("[\\p{Cntrl}]", "").replaceAll("[^A-Za-z0-9._-]", "_");
        if (leaf.length() > 120) leaf = leaf.substring(0, 120);
        String lower = leaf.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") ? leaf : null;
    }

    private static boolean hasValidImageSignature(File file, String filename) throws IOException {
        byte[] header = new byte[12];
        int count;
        try (FileInputStream input = new FileInputStream(file)) { count = input.read(header); }
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if ("jpg".equals(ext) || "jpeg".equals(ext)) return count >= 3 && (header[0] & 255) == 0xFF && (header[1] & 255) == 0xD8 && (header[2] & 255) == 0xFF;
        if ("png".equals(ext)) return count >= 8 && (header[0] & 255) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G' && (header[4] & 255) == 0x0D && (header[5] & 255) == 0x0A && (header[6] & 255) == 0x1A && (header[7] & 255) == 0x0A;
        return "webp".equals(ext) && count >= 12 && ascii(header, 0, "RIFF") && ascii(header, 8, "WEBP");
    }

    private static boolean ascii(byte[] bytes, int offset, String value) {
        for (int i = 0; i < value.length(); i++) if (bytes[offset + i] != (byte) value.charAt(i)) return false;
        return true;
    }

    private static long copyUntilBoundary(InputStream input, OutputStream output, byte[] delimiter, long limit) throws IOException {
        int[] prefix = new int[delimiter.length];
        for (int i = 1, j = 0; i < delimiter.length; i++) {
            while (j > 0 && delimiter[i] != delimiter[j]) j = prefix[j - 1];
            if (delimiter[i] == delimiter[j]) j++;
            prefix[i] = j;
        }
        byte[] pending = new byte[delimiter.length];
        int matched = 0;
        long written = 0;
        int value;
        while ((value = input.read()) >= 0) {
            byte next = (byte) value;
            while (matched > 0 && next != delimiter[matched]) {
                int fallback = prefix[matched - 1];
                int flush = matched - fallback;
                output.write(pending, 0, flush);
                written += flush;
                if (written > limit) throw new UploadTooLargeException();
                System.arraycopy(pending, flush, pending, 0, fallback);
                matched = fallback;
            }
            if (next == delimiter[matched]) {
                pending[matched++] = next;
                if (matched == delimiter.length) return written;
            } else {
                output.write(next);
                if (++written > limit) throw new UploadTooLargeException();
            }
        }
        throw new IOException("Multipart boundary was not found.");
    }

    private static void drain(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        while (input.read(buffer) >= 0) { }
    }

    private static String boundaryFrom(String contentType) {
        for (String parameter : contentType.split(";")) {
            String part = parameter.trim();
            if (part.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                String value = part.substring(part.indexOf('=') + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
                return value.matches("[A-Za-z0-9'()+_,./:=?-]+") ? value : null;
            }
        }
        return null;
    }

    private static Map<String, String> readHeaders(InputStream input) throws IOException {
        Map<String, String> result = new HashMap<>();
        int bytes = 0;
        for (int i = 0; i < 100; i++) {
            String line = readLine(input, HEADER_LIMIT);
            if (line == null) throw new IOException("Incomplete HTTP headers.");
            bytes += line.length() + 2;
            if (bytes > HEADER_LIMIT) throw new IOException("HTTP headers are too large.");
            if (line.isEmpty()) return result;
            int colon = line.indexOf(':');
            if (colon <= 0) throw new IOException("Malformed HTTP header.");
            result.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        throw new IOException("Too many HTTP headers.");
    }

    private static String readLine(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) >= 0) {
            if (value == '\n') {
                byte[] data = bytes.toByteArray();
                int length = data.length > 0 && data[data.length - 1] == '\r' ? data.length - 1 : data.length;
                return new String(data, 0, length, StandardCharsets.ISO_8859_1);
            }
            bytes.write(value);
            if (bytes.size() > limit) throw new IOException("HTTP line is too long.");
        }
        return bytes.size() == 0 ? null : bytes.toString("ISO-8859-1");
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String createToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static String uploadPage() {
        return "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><meta charset=utf-8>" +
                "<title>Liquid Home wallpaper transfer</title><style>body{font:17px system-ui,sans-serif;max-width:620px;margin:8vh auto;padding:24px;background:#10141d;color:#f5f7fb}" +
                "main{background:#1c2431;padding:28px;border-radius:20px}button,input{font:inherit}button{padding:12px 20px;border:0;border-radius:10px;background:#79baff;color:#08111b;font-weight:700}" +
                "progress{width:100%;height:20px}#status{min-height:2em;color:#d9e7f7}.hint{color:#b7c4d2}</style></head><body><main>" +
                "<h1>Send a wallpaper to Liquid Home</h1><p class=hint>Choose a JPG, JPEG, PNG or WebP image. It will be saved to your Fire TV wallpaper collection and will not be applied automatically.</p>" +
                "<form id=form><input id=file type=file accept='.jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp' required><p><button id=send type=submit>Upload image</button></p></form>" +
                "<progress id=progress max=100 value=0></progress><p id=status role=status aria-live=polite>Select an image to begin.</p>" +
                "<script>const f=document.getElementById('form'),i=document.getElementById('file'),b=document.getElementById('send'),p=document.getElementById('progress'),s=document.getElementById('status');" +
                "f.addEventListener('submit',e=>{e.preventDefault();if(!i.files.length)return;const x=i.files[0],q=new XMLHttpRequest(),d=new FormData();d.append('image',x,x.name);b.disabled=true;p.value=0;s.textContent='Uploading '+x.name+'…';" +
                "q.upload.onprogress=e=>{if(e.lengthComputable){p.value=Math.round(e.loaded*100/e.total);s.textContent=p.value>=100?'Received by Fire TV. Saving…':'Uploading… '+p.value+'%'}};q.upload.onload=()=>{p.value=100;s.textContent='Received by Fire TV. Saving…'};q.onload=()=>{b.disabled=false;try{const r=JSON.parse(q.responseText);if(q.status>=200&&q.status<300&&r.ok){p.value=100;s.textContent='Upload successful — '+r.filename+' is saved on your Fire TV. You can now close this page.';f.reset();return}}catch(e){}s.textContent=q.responseText||'Upload failed. Try another image.'};" +
                "q.onerror=()=>{b.disabled=false;s.textContent='Could not reach the Fire TV. Keep this page open and try again.'};q.open('POST',location.pathname);q.send(d)});</script></main></body></html>";
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "");
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isEmpty() ? "network error" : e.getMessage();
    }

    private void notifyUpload(String name, boolean success, String message) {
        if (listener != null) listener.onUpload(name, success, message);
    }

    private static void respond(OutputStream output, int status, String body, String contentType) throws IOException {
        respond(output, status, body, contentType, "");
    }

    private static void respond(OutputStream output, int status, String body, String contentType, String extraHeaders) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 302 ? "Found" : status == 400 ? "Bad Request" : status == 403 ? "Forbidden" : status == 404 ? "Not Found" : status == 411 ? "Length Required" : status == 413 ? "Payload Too Large" : status == 415 ? "Unsupported Media Type" : "Server Error";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n" +
                "Content-Type: " + contentType + "\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n" +
                "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" +
                "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'\r\n" + extraHeaders + "\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
        output.flush();
    }

    @Override public void close() {
        stopReason = "Transfer session stopped.";
        running.set(false);
        closeSocket();
        Socket client = currentClient;
        if (client != null) try { client.close(); } catch (IOException ignored) { }
        Thread thread = serverThread;
        if (thread != null) thread.interrupt();
    }

    private void closeSocket() {
        ServerSocket socket = serverSocket;
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    private static final class LimitedInputStream extends InputStream {
        private final InputStream source;
        private long remaining;
        LimitedInputStream(InputStream source, long length) { this.source = source; this.remaining = length; }
        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = source.read();
            if (value >= 0) remaining--;
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int count = source.read(bytes, offset, (int) Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }
    }

    private static final class UploadTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
