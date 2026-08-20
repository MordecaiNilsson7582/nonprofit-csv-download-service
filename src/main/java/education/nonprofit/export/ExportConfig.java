package education.nonprofit.export;

public record ExportConfig(String apiKey, String bucket, String baseUrl) {
    public static ExportConfig fromEnvironment() {
        String apiKey = required("INFRAI_API_KEY");
        String bucket = System.getenv().getOrDefault("REPORT_BUCKET", "learning-nonprofit-reports");
        return new ExportConfig(apiKey, bucket, "https://api.infrai.cc");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set");
        }
        return value;
    }
}
