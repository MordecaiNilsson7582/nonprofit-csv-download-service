package education.nonprofit.export;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InfraiStorageClient {
    private static final int MAX_ATTEMPTS = 4;
    private final HttpClient http;
    private final ExportConfig config;

    public InfraiStorageClient(ExportConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    InfraiStorageClient(ExportConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    public void createBucket() throws IOException, InterruptedException {
        call("POST", "/v1/storage/bucket/create", Map.of("name", config.bucket()));
    }

    public SignedRequest presignPut(String key, String contentType, int byteCount, String requestId)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "put");
        body.put("expires_seconds", 600);
        body.put("content_type", contentType);
        body.put("max_bytes", byteCount);
        body.put("idempotency_key", requestId);
        return signedRequest(call("POST", objectPath(key), body), "PUT");
    }

    public SignedRequest presignDownload(String key, String filename)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "get");
        body.put("expires_seconds", 900);
        body.put("response_disposition", "attachment; filename=\"" + filename + "\"");
        return signedRequest(call("POST", objectPath(key), body), "GET");
    }

    public void upload(SignedRequest signed, byte[] content, String contentType)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(signed.url()))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", contentType)
                .method("PUT", HttpRequest.BodyPublishers.ofByteArray(content))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Signed upload returned HTTP " + response.statusCode());
        }
    }

    private Map<String, Object> call(String method, String path, Map<String, Object> body)
            throws IOException, InterruptedException {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> envelope = Json.object(Json.parse(response.body()));

            if (response.statusCode() == 429 && attempt + 1 < MAX_ATTEMPTS) {
                Thread.sleep(retryDelayMillis(response, attempt));
                continue;
            }
            if (!Boolean.TRUE.equals(envelope.get("ok"))) {
                Map<String, Object> error = Json.object(envelope.get("error"));
                throw new InfraiException(String.valueOf(error.get("code")), error, response.statusCode());
            }
            if (response.statusCode() >= 500) {
                throw new IOException("Infrai transport response HTTP " + response.statusCode());
            }
            return Json.object(envelope.get("data"));
        }
        throw new IOException("Retry attempts exhausted");
    }

    private long retryDelayMillis(HttpResponse<?> response, int attempt) {
        return response.headers().firstValue("Retry-After")
                .map(value -> {
                    try { return Long.parseLong(value) * 1000L; }
                    catch (NumberFormatException ignored) { return 250L * (1L << attempt); }
                })
                .orElse(250L * (1L << attempt));
    }

    private SignedRequest signedRequest(Map<String, Object> data, String fallbackMethod) {
        String url = String.valueOf(data.get("url"));
        Object method = data.get("method");
        return new SignedRequest(url, method == null ? fallbackMethod : String.valueOf(method));
    }

    private String objectPath(String key) {
        // API shape: /v1/storage/object/presign/{bucket}/{key}; both identifiers are path segments.
        return "/v1/storage/object/presign/" + segment(config.bucket()) + "/" + segment(key);
    }

    private static String segment(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte part : value.getBytes(StandardCharsets.UTF_8)) {
            int c = part & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                    (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(String.format("%02X", c));
            }
        }
        return encoded.toString();
    }

    public record SignedRequest(String url, String method) { }

    public static final class InfraiException extends IOException {
        private final String code;
        private final Map<String, Object> detail;
        private final int statusCode;

        InfraiException(String code, Map<String, Object> detail, int statusCode) {
            super(code + ": " + detail.getOrDefault("message", "request rejected"));
            this.code = code;
            this.detail = Map.copyOf(detail);
            this.statusCode = statusCode;
        }

        public String code() { return code; }
        public Map<String, Object> detail() { return detail; }
        public int statusCode() { return statusCode; }
    }

    static final class Json {
        private final String input;
        private int position;

        private Json(String input) { this.input = input; }

        static Object parse(String input) throws IOException {
            Json parser = new Json(input);
            Object value = parser.value();
            parser.space();
            if (parser.position != input.length()) throw new IOException("Unexpected JSON suffix");
            return value;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> object(Object value) throws IOException {
            if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
            throw new IOException("Expected JSON object");
        }

        static String write(Map<String, Object> values) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(quote(entry.getKey())).append(':');
                Object value = entry.getValue();
                out.append(value instanceof Number || value instanceof Boolean ? value : quote(String.valueOf(value)));
            }
            return out.append('}').toString();
        }

        private Object value() throws IOException {
            space();
            if (position >= input.length()) throw new IOException("Empty JSON response");
            return switch (input.charAt(position)) {
                case '{' -> objectValue();
                case '[' -> arrayValue();
                case '"' -> stringValue();
                case 't' -> literal("true", true);
                case 'f' -> literal("false", false);
                case 'n' -> literal("null", null);
                default -> numberValue();
            };
        }

        private Map<String, Object> objectValue() throws IOException {
            position++;
            Map<String, Object> result = new LinkedHashMap<>();
            space();
            if (take('}')) return result;
            do {
                space();
                String key = stringValue();
                space();
                require(':');
                result.put(key, value());
                space();
            } while (take(','));
            require('}');
            return result;
        }

        private List<Object> arrayValue() throws IOException {
            position++;
            List<Object> result = new ArrayList<>();
            space();
            if (take(']')) return result;
            do { result.add(value()); space(); } while (take(','));
            require(']');
            return result;
        }

        private String stringValue() throws IOException {
            require('"');
            StringBuilder out = new StringBuilder();
            while (position < input.length()) {
                char c = input.charAt(position++);
                if (c == '"') return out.toString();
                if (c == '\\') {
                    if (position >= input.length()) throw new IOException("Bad JSON escape");
                    char escaped = input.charAt(position++);
                    if (escaped == 'u') {
                        if (position + 4 > input.length()) throw new IOException("Bad unicode escape");
                        out.append((char) Integer.parseInt(input.substring(position, position + 4), 16));
                        position += 4;
                    } else {
                        out.append(switch (escaped) {
                            case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                            case 'b' -> '\b'; case 'f' -> '\f'; case '"' -> '"';
                            case '\\' -> '\\'; case '/' -> '/';
                            default -> throw new IOException("Bad JSON escape");
                        });
                    }
                } else out.append(c);
            }
            throw new IOException("Unclosed JSON string");
        }

        private Object numberValue() throws IOException {
            int start = position;
            while (position < input.length() && "-+0123456789.eE".indexOf(input.charAt(position)) >= 0) position++;
            try {
                String number = input.substring(start, position);
                return number.contains(".") || number.contains("e") || number.contains("E")
                        ? Double.parseDouble(number) : Long.parseLong(number);
            } catch (NumberFormatException error) { throw new IOException("Bad JSON number", error); }
        }

        private Object literal(String text, Object value) throws IOException {
            if (!input.startsWith(text, position)) throw new IOException("Bad JSON literal");
            position += text.length();
            return value;
        }

        private void space() { while (position < input.length() && Character.isWhitespace(input.charAt(position))) position++; }
        private boolean take(char expected) { if (position < input.length() && input.charAt(position) == expected) { position++; return true; } return false; }
        private void require(char expected) throws IOException { if (!take(expected)) throw new IOException("Expected " + expected); }
        private static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; }
    }
}
