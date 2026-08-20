package education.nonprofit.export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class NonprofitCsvExportService {
    private static final String CSV_TYPE = "text/csv; charset=utf-8";
    private final InfraiStorageClient storage;

    public NonprofitCsvExportService(InfraiStorageClient storage) {
        this.storage = storage;
    }

    public ExportResult export(ReportRequest request) throws IOException, InterruptedException {
        ExportDocument document = render(request);
        String requestId = UUID.randomUUID().toString();
        String key = "reports/" + request.organizationSlug() + "/" + requestId + ".csv";
        byte[] bytes = document.csv().getBytes(StandardCharsets.UTF_8);

        storage.createBucket();
        InfraiStorageClient.SignedRequest upload = storage.presignPut(key, CSV_TYPE, bytes.length, requestId);
        storage.upload(upload, bytes, CSV_TYPE);
        InfraiStorageClient.SignedRequest download = storage.presignDownload(key, document.filename());
        return new ExportResult(document.kind(), document.rowCount(), download.url(), document.filename());
    }

    ExportDocument render(ReportRequest request) {
        if (request.rows().isEmpty()) throw new IllegalArgumentException("At least one report row is required");
        StringBuilder csv = new StringBuilder("name,email,amount_or_hours,next_action\r\n");
        for (ReportRow row : request.rows()) {
            csv.append(cell(row.name())).append(',').append(cell(row.email())).append(',')
                    .append(cell(row.amountOrHours())).append(',').append(cell(row.nextAction())).append("\r\n");
        }
        String filename = request.organizationSlug() + "-" + request.kind().fileLabel() + "-" + LocalDate.now() + ".csv";
        return new ExportDocument(request.kind(), request.rows().size(), filename, csv.toString());
    }

    private static String cell(String value) {
        String safe = value;
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        if (safe.indexOf(',') >= 0 || safe.indexOf('"') >= 0 || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    public enum ReportKind {
        DONOR_RECEIPTS("donor-receipts"),
        VOLUNTEER_REMINDERS("volunteer-reminders"),
        CAMPAIGN_REPORT("campaign-report");

        private final String fileLabel;
        ReportKind(String fileLabel) { this.fileLabel = fileLabel; }
        String fileLabel() { return fileLabel; }
    }

    public record ReportRequest(String organizationSlug, ReportKind kind, List<ReportRow> rows) {
        public ReportRequest {
            if (organizationSlug == null || !organizationSlug.matches("[a-z0-9-]+")) {
                throw new IllegalArgumentException("organizationSlug must use lowercase letters, digits, and hyphens");
            }
            rows = List.copyOf(rows);
        }
    }

    public record ReportRow(String name, String email, String amountOrHours, String nextAction) { }
    public record ExportResult(ReportKind kind, int rowCount, String downloadUrl, String filename) { }
    record ExportDocument(ReportKind kind, int rowCount, String filename, String csv) { }
}
