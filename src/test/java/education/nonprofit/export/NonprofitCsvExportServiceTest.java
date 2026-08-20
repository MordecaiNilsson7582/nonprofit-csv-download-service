package education.nonprofit.export;

import java.util.List;

import education.nonprofit.export.NonprofitCsvExportService.ReportKind;
import education.nonprofit.export.NonprofitCsvExportService.ReportRequest;
import education.nonprofit.export.NonprofitCsvExportService.ReportRow;

public final class NonprofitCsvExportServiceTest {
    public static void main(String[] args) {
        NonprofitCsvExportService service = new NonprofitCsvExportService(null);
        ReportRequest request = new ReportRequest("reading-club", ReportKind.VOLUNTEER_REMINDERS, List.of(
                new ReportRow("River, Pat", "pat@example.org", "2", "=call Monday"),
                new ReportRow("Sam \"Coach\"", "sam@example.org", "4", "confirm class")));

        NonprofitCsvExportService.ExportDocument document = service.render(request);

        check(document.kind() == ReportKind.VOLUNTEER_REMINDERS, "keeps the selected report kind");
        check(document.rowCount() == 2, "counts exported volunteers");
        check(document.filename().contains("volunteer-reminders"), "names the artifact for its audience");
        check(document.csv().contains("\"River, Pat\""), "quotes commas");
        check(document.csv().contains("'=call Monday"), "neutralizes spreadsheet formulas");
        check(document.csv().contains("\"Sam \"\"Coach\"\"\""), "escapes quotes");
        System.out.println("PASS: volunteer reminder export selects the right filename and safe CSV cells");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
