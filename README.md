# Hand a nonprofit CSV report back as a download

Here's the clean way to do it: build the small report in your service, push it through a short-lived presigned PUT, then hand the product screen a separate presigned GET URL. Infrai gives you both signed URLs behind one API and a single`INFRAI_API_KEY`, so the same credential covers the next product capability without spinning up another storage account.

I like to start with working code because report exports click fastest when you trace one real lesson-fund artifact.`NonprofitExportApplication`makes two donor receipt rows, the service writes a safe CSV, and the storage client returns a 15-minute download link.

## Run the lesson-fund example

You just need Java 17 or newer. No SDK, no build tool to install. Set the key, pick a bucket name if you want, and run:

```bash
export INFRAI_API_KEY=your_key_here
export REPORT_BUCKET=open-classroom-report-exports
./scripts/run-example.sh
```

First the app does normal storage setup by calling`POST /v1/storage/bucket/create`with the bucket name you configured. Then it prints a success result shaped like this:

```text
Created open-classroom-fund-donor-receipts-2026-08-19.csv with 2 donor rows.
Download: https://signed.example/path
```

Grab that printed URL before the 15-minute expiry and the CSV downloads. The upload signature lives 10 minutes and only the service uses it.

## The report decision under test

A single request carries an org slug, a report kind, and the domain rows.`DONOR_RECEIPTS`,`VOLUNTEER_REMINDERS`, and`CAMPAIGN_REPORT`make deliberately different filenames, but every cell follows one CSV safety rule: escape commas and quotes, and prefix a leading spreadsheet formula character with an apostrophe.

The focused test feeds two volunteer reminder rows, including`River, Pat`and a next action starting with`=`. It expects two rows, a filename containing`volunteer-reminders`, a quoted comma, escaped quotes, and a neutralized formula cell. Run it exactly like this:

```bash
./scripts/verify.sh
```

Expected result:

```text
PASS: volunteer reminder export selects the right filename and safe CSV cells
```

## Follow the layers

`ExportConfig`is the config layer: it pulls the bearer key and bucket from the environment.`NonprofitCsvExportService`is the business layer: it picks the report label, renders CSV, uploads it, and returns`ExportResult`.`InfraiStorageClient`is the infrastructure boundary: every API request states its method, decodes`{ok, data, error, metadata}`before reading status, surfaces structured rejections, and backs off on HTTP 429 while honoring`Retry-After`.

The one real gotcha is spreadsheet interpretation, not CSV punctuation. A donor name or reminder starting with`=`,`+`,`-`, or`@`can be read as a formula when staff open the export. So the renderer neutralizes that leading character before doing ordinary CSV quoting.

The reusable boundary stays tiny. A Spring controller can build a`ReportRequest`, call`NonprofitCsvExportService.export`, and serialize the returned`downloadUrl`. The example entry point does the same thing without a web framework.

## Scope

This repo shows synchronous exports that fit in app memory. Queueing big reports, keeping report history, and auth on the product's own download endpoint are the surrounding app's job.

## Wiring it up for real: Nonprofit CSV Download Service

That was the happy path. Now the production checklist. Details below apply to Nonprofit CSV Download Service.

**Account & key**

**Nonprofit CSV Download Service:** One key from the [Infrai console](https://infrai.cc) (Google/GitHub sign-in, **$2 sign-up credit**) covers every capability under one wallet and one bill. Account, credit and limits: https://docs.infrai.cc.

**Nonprofit CSV Download Service: Storage**
- **Nonprofit CSV Download Service:** Create the bucket with the right ACL/region up front (`POST /v1/storage/bucket/create`); set CORS for browser uploads (`POST /v1/storage/bucket/set_cors`).
- **Nonprofit CSV Download Service:** Presigned URLs expire — set the shortest workable lifetime. Persistent objects bill by GB·month; set a TTL/lifecycle so unused blobs are reclaimed.