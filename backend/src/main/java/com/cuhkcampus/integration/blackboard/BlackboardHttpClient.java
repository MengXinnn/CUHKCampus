package com.cuhkcampus.integration.blackboard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Stateless, authenticated HTTP access to this school's Blackboard Learn API. */
public class BlackboardHttpClient {
    private final URI baseUrl;
    private final ObjectMapper json;
    private final String curl;

    public BlackboardHttpClient(URI baseUrl, ObjectMapper json) { this(baseUrl, json, "curl"); }
    public BlackboardHttpClient(URI baseUrl, ObjectMapper json, String curl) {
        this.baseUrl = baseUrl;
        this.json = json;
        this.curl = curl;
    }

    public Map<String, Object> user(String cookieHeader) {
        return object(cookieHeader, "/learn/api/public/v1/users/me");
    }

    public Map<String, Object> object(String cookieHeader, String path) {
        Response response = get(cookieHeader, path);
        if (!response.contentType().toLowerCase(Locale.ROOT).contains("json"))
            throw new SessionExpiredException();
        try { return json.readValue(response.body(), new TypeReference<>() {}); }
        catch (IOException e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "BB 返回了无法读取的 JSON", e); }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> list(String cookieHeader, String path) {
        var items = new ArrayList<Map<String, Object>>();
        String next = path;
        while (next != null) {
            Map<String, Object> page = object(cookieHeader, next);
            items.addAll((List<Map<String, Object>>) page.get("results"));
            Map<String, Object> paging = (Map<String, Object>) page.get("paging");
            next = paging == null ? null : (String) paging.get("nextPage");
        }
        return items;
    }

    public byte[] bytes(String cookieHeader, String path) { return get(cookieHeader, path).body(); }
    public void download(String cookieHeader, String path, Path destination) { get(cookieHeader, path, destination); }

    public record DownloadProgress(long loaded, long total) {}

    public void download(String cookieHeader, String path, Path destination, long expectedSize,
            Consumer<DownloadProgress> progress) {
        get(cookieHeader, path, destination, expectedSize, progress);
    }

    private record Response(int status, String contentType, Map<String, String> headers, byte[] body) {}

    private Response get(String cookieHeader, String path) {
        return get(cookieHeader, path, null);
    }

    private Response get(String cookieHeader, String path, Path destination) {
        return get(cookieHeader, path, destination, 0, null);
    }

    private Response get(String cookieHeader, String path, Path destination, long expectedSize,
            Consumer<DownloadProgress> progress) {
        URI uri = baseUrl.resolve(path);
        for (int redirects = 0; redirects <= 8; redirects++) {
            Response response = request(uri, sameOrigin(uri) ? cookieHeader : null, destination, expectedSize, progress);
            if (response.status() == 401) throw new SessionExpiredException();
            if (response.status() >= 300 && response.status() < 400) {
                uri = uri.resolve(response.headers().get("location"));
                if (uri.getPath().contains("/login") || "sts.cuhk.edu.cn".equalsIgnoreCase(uri.getHost()))
                    throw new SessionExpiredException();
                continue;
            }
            if (response.status() < 200 || response.status() >= 300) {
                String message = "BB 接口请求失败（HTTP " + response.status() + "）：" + uri.getPath();
                throw new ResponseStatusException(response.status() == 403 ? HttpStatus.FORBIDDEN : HttpStatus.BAD_GATEWAY, message);
            }
            return response;
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "BB 附件重定向次数过多");
    }

    private boolean sameOrigin(URI uri) {
        return baseUrl.getScheme().equalsIgnoreCase(uri.getScheme()) && baseUrl.getHost().equalsIgnoreCase(uri.getHost())
            && baseUrl.getPort() == uri.getPort();
    }

    private Response request(URI uri, String cookieHeader, Path destination, long expectedSize, Consumer<DownloadProgress> progress) {
        Path body = null, headers = null, errors = null;
        Process process = null;
        try {
            body = destination == null ? Files.createTempFile("campus-bb-body-", ".tmp") : destination;
            headers = Files.createTempFile("campus-bb-headers-", ".tmp");
            errors = Files.createTempFile("campus-bb-error-", ".tmp");
            process = new ProcessBuilder(curl, "--silent", "--show-error", "--compressed",
                "--connect-timeout", "20", "--max-time", "180", "--config", "-",
                "--dump-header", headers.toString(), "--output", body.toString(),
                "--write-out", "%{http_code}\n%{content_type}")
                .redirectError(errors.toFile()).start();
            String config = "url = \"" + quoted(uri.toString()) + "\"\n";
            if (cookieHeader != null) config += "header = \"Cookie: " + quoted(cookieHeader) + "\"\n";
            try (var input = process.getOutputStream()) { input.write(config.getBytes(StandardCharsets.UTF_8)); }
            while (!process.waitFor(150, TimeUnit.MILLISECONDS)) {
                if (progress != null) {
                    String responseHeaders = Files.readString(headers);
                    if (responseHeaders.matches("(?s).*HTTP/[^ ]+ 2[0-9][0-9].*")) {
                        long total = expectedSize;
                        for (String line : responseHeaders.split("\r?\n"))
                            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:"))
                                total = Long.parseLong(line.substring(line.indexOf(':') + 1).strip());
                        progress.accept(new DownloadProgress(Files.size(body), total));
                    }
                }
            }
            String metadata = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.exitValue();
            if (exit != 0) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接 BB 服务（curl " + exit + "）");
            String[] values = metadata.split("\n", 2);
            if (progress != null && Integer.parseInt(values[0]) >= 200 && Integer.parseInt(values[0]) < 300) {
                long size = Files.size(body);
                progress.accept(new DownloadProgress(size, size));
            }
            var parsedHeaders = new LinkedHashMap<String, String>();
            for (String line : Files.readAllLines(headers)) {
                int separator = line.indexOf(':');
                if (separator > 0) parsedHeaders.put(line.substring(0, separator).toLowerCase(Locale.ROOT), line.substring(separator + 1).strip());
            }
            return new Response(Integer.parseInt(values[0]), values.length > 1 ? values[1] : "", parsedHeaders,
                destination == null ? Files.readAllBytes(body) : new byte[0]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "BB 请求已中断", e);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法运行 BB HTTP 客户端，请确认 curl 已安装", e);
        } finally {
            if (process != null) process.destroy();
            try {
                if (body != null && destination == null) Files.deleteIfExists(body);
                if (headers != null) Files.deleteIfExists(headers);
                if (errors != null) Files.deleteIfExists(errors);
            } catch (IOException e) { throw new IllegalStateException("无法清理 BB 请求临时文件", e); }
        }
    }

    private String quoted(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "");
    }

    public static class SessionExpiredException extends RuntimeException {
        public SessionExpiredException() { super("BB 会话已过期，请重新绑定"); }
    }
}
