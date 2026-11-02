package ru.hikcamscan;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.*;

final class CameraHttp {
    record Credential(String user, String password) { }
    record Response(int code, byte[] body, String challenge) { }
    static class AuthException extends IOException { AuthException() { super("Неверный логин/пароль или доступ запрещён"); } }
    static Response request(String url, String method, String body, String action, Credential credential, String ip) throws Exception {
        URI uri = Camera.sameHost(url, ip);
        Response response = send(uri, method, body, action, null);
        if (response.code == 401 && credential != null) {
            String auth = authorization(response.challenge, method, uri, credential);
            response = send(uri, method, body, action, auth);
        }
        if (response.code == 401 || response.code == 403) throw new AuthException();
        if (response.code == 423 || response.code == 429) throw new IOException("Камера ограничила вход. Проверка остановлена.");
        return response;
    }
    private static Response send(URI uri, String method, String body, String action, String authorization) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
        HttpURLConnection c = (HttpURLConnection)uri.toURL().openConnection();
        c.setConnectTimeout(4500); c.setReadTimeout(6500); c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method);
        try {
            if (authorization != null) c.setRequestProperty("Authorization", authorization);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8; action=\"" + action + "\"");
                c.setRequestProperty("SOAPAction", "\"" + action + "\"");
                try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            String challenge = c.getHeaderField("WWW-Authenticate");
            InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (stream != null) try (InputStream in = stream) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    if (bytes.size() + n > 12 * 1024 * 1024) throw new IOException("Ответ камеры слишком большой");
                    bytes.write(buffer, 0, n);
                }
            }
            return new Response(code, bytes.toByteArray(), challenge == null ? "" : challenge);
        } finally { c.disconnect(); }
    }
    static String authorization(String challenge, String method, URI uri, Credential cr) throws Exception {
        if (challenge.regionMatches(true, 0, "Basic", 0, 5))
            return "Basic " + Base64.getEncoder().encodeToString((cr.user + ":" + cr.password).getBytes(StandardCharsets.UTF_8));
        if (!challenge.regionMatches(true, 0, "Digest", 0, 6)) throw new IOException("Неизвестная HTTP-аутентификация");
        Map<String,String> fields = new HashMap<>();
        Matcher m = Pattern.compile("([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))").matcher(challenge.substring(6));
        while (m.find()) fields.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2) != null ? m.group(2) : m.group(3));
        String realm = fields.get("realm"), nonce = fields.get("nonce");
        if (realm == null || nonce == null) throw new IOException("Некорректный Digest challenge");
        String algorithm = fields.getOrDefault("algorithm", "MD5");
        String hash = algorithm.toUpperCase(Locale.ROOT).startsWith("SHA-256") ? "SHA-256" : "MD5";
        if (!Arrays.asList("MD5", "MD5-SESS", "SHA-256", "SHA-256-SESS").contains(algorithm.toUpperCase(Locale.ROOT)))
            throw new IOException("Неподдерживаемый Digest algorithm");
        String cnonce = UUID.randomUUID().toString().replace("-", "");
        String target = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) target += "?" + uri.getRawQuery();
        String ha1 = hex(hash, cr.user + ":" + realm + ":" + cr.password);
        if (algorithm.toLowerCase(Locale.ROOT).endsWith("-sess")) ha1 = hex(hash, ha1 + ":" + nonce + ":" + cnonce);
        boolean qop = fields.containsKey("qop");
        if (qop && !Arrays.asList(fields.get("qop").toLowerCase(Locale.ROOT).split("\\s*,\\s*")).contains("auth"))
            throw new IOException("Камера требует неподдерживаемый qop");
        String response = hex(hash, ha1 + ":" + nonce + ":" + (qop ? "00000001:" + cnonce + ":auth:" : "") + hex(hash, method + ":" + target));
        String header = "Digest username=" + quote(cr.user) + ", realm=" + quote(realm) + ", nonce=" + quote(nonce)
                + ", uri=" + quote(target) + ", response=" + quote(response) + ", algorithm=" + algorithm;
        if (qop) header += ", qop=auth, nc=00000001, cnonce=" + quote(cnonce);
        else if (algorithm.toLowerCase(Locale.ROOT).endsWith("-sess")) header += ", cnonce=" + quote(cnonce);
        if (fields.containsKey("opaque")) header += ", opaque=" + quote(fields.get("opaque"));
        return header;
    }
    private static String quote(String s) throws IOException {
        if (s.contains("\r") || s.contains("\n")) throw new IOException("Недопустимый заголовок");
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
    static String hex(String algorithm, String s) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance(algorithm).digest(s.getBytes(StandardCharsets.UTF_8))) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
}
