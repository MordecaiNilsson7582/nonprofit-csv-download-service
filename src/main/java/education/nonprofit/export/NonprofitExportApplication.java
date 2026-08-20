package education.nonprofit.export;

import java.util.List;

import education.nonprofit.export.NonprofitCsvExportService.ReportKind;
import education.nonprofit.export.NonprofitCsvExportService.ReportRequest;
import education.nonprofit.export.NonprofitCsvExportService.ReportRow;

public final class NonprofitExportApplication {
    private NonprofitExportApplication() { }

    public static void main(String[] args) throws Exception {
        ExportConfig config = ExportConfig.fromEnvironment();
        NonprofitCsvExportService service = new NonprofitCsvExportService(new InfraiStorageClient(config));
        ReportRequest lessonFundReport = new ReportRequest(
                "open-classroom-fund",
                ReportKind.DONOR_RECEIPTS,
                List.of(
                        new ReportRow("Avery Chen", "avery@example.org", "75.00", "receipt issued"),
                        new ReportRow("Morgan Lee", "morgan@example.org", "125.00", "receipt issued")));

        NonprofitCsvExportService.ExportResult result = service.export(lessonFundReport);
        System.out.println("Created " + result.filename() + " with " + result.rowCount() + " donor rows.");
        System.out.println("Download: " + result.downloadUrl());
    }
}
