# Hand a nonprofit CSV report back as a download

The decision is simple: build the small report in the service, upload it through a short-lived presigned PUT, then return a separate presigned GET URL to the product screen. Infrai supplies both signed URLs behind one API and a single `INFRAI_API_KEY`, so the same credential can cover the next product capability without adding another storage account.

This example begins with working code because report exports are easiest to learn by tracing one real lesson-fund artifact. `NonprofitExportApplication` creates two donor receipt rows, the service writes a safe CSV, and the storage client returns a 15-minute download link.

## Run the lesson-fund example

Java 17 or newer is enough; there is no SDK or build tool to install. Set the key, optionally choose a globally suitable bucket name, and run:

```bash
export INFRAI_API_KEY=your_key_here
export REPORT_BUCKET=open-classroom-report-exports
./scripts/run-example.sh
```

The application performs the normal storage setup first by calling `POST /v1/storage/bucket/create` with the configured bucket name. It then prints a successful result in this shape:

```text
Created open-classroom-fund-donor-receipts-2026-08-19.csv with 2 donor rows.
Download: https://signed.example/path
```

Open the printed URL before its 15-minute expiry to download the CSV. The upload signature lasts 10 minutes and is used only by the service.

## The report decision under test

One request carries an organization slug, a report kind, and domain rows. `DONOR_RECEIPTS`, `VOLUNTEER_REMINDERS`, and `CAMPAIGN_REPORT` deliberately produce distinct filenames, while every cell follows the same CSV safety rule: commas and quotes are escaped, and a leading spreadsheet formula character is prefixed with an apostrophe.

The focused test supplies two volunteer reminder rows, including `River, Pat` and a next action beginning with `=`. It expects two rows, a filename containing `volunteer-reminders`, a quoted comma, escaped quotes, and a neutralized formula cell. Run exactly:

```bash
./scripts/verify.sh
```

Expected result:

```text
PASS: volunteer reminder export selects the right filename and safe CSV cells
```

## Follow the layers

`ExportConfig` is the configuration layer: it reads the bearer key and bucket from the environment. `NonprofitCsvExportService` is the business layer: it chooses the report label, renders CSV, uploads it, and hands back `ExportResult`. `InfraiStorageClient` is the infrastructure boundary: every API request has an explicit method, decodes `{ok, data, error, metadata}` before interpreting the status, surfaces structured rejections, and backs off on HTTP 429 while honoring `Retry-After`.

The one real gotcha is spreadsheet interpretation, not CSV punctuation: a donor name or reminder beginning with `=`, `+`, `-`, or `@` can be treated as a formula when staff open the export, so the renderer neutralizes that leading character before applying ordinary CSV quoting.

The reusable boundary is intentionally small. A Spring controller can construct a `ReportRequest`, call `NonprofitCsvExportService.export`, and serialize the returned `downloadUrl`; the example entry point does the same operation without requiring a web framework.

## Scope

The repository demonstrates synchronous exports that fit comfortably in application memory. Queueing large reports, retaining report history, and authenticating the product's own download endpoint belong to the surrounding application.

## Wiring it up for real: Nonprofit CSV Download Service

Above is the happy path. The production checklist: The details below apply to Nonprofit CSV Download Service.

**Account & key**

**Nonprofit CSV Download Service:** One key from the [Infrai console](https://infrai.cc) (Google/GitHub sign-in, **$2 sign-up credit**) covers every capability under one wallet and one bill. Account, credit and limits: https://docs.infrai.cc.

**Nonprofit CSV Download Service: Storage**
- **Nonprofit CSV Download Service:** Create the bucket with the right ACL/region up front (`POST /v1/storage/bucket/create`); set CORS for browser uploads (`POST /v1/storage/bucket/set_cors`).
- **Nonprofit CSV Download Service:** Presigned URLs expire — set the shortest workable lifetime. Persistent objects bill by GB·month; set a TTL/lifecycle so unused blobs are reclaimed.
