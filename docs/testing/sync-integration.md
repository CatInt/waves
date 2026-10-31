# Sync integration and upgrade safety

How cross-device sync and the on-disk database are tested without real Google credentials, what was found,
and the compatibility rules that keep user data safe across app versions.

Run everything with the standard verify (`./gradlew :composeApp:desktopTest ...`, see CLAUDE.md). The schema
check is `./gradlew :composeApp:verifySqlDelightMigration` (also part of `check`).

## Fake Google Drive

`composeApp/src/desktopTest/kotlin/io/waves/sync/FakeDriveServer.kt` is a real local HTTP server
(`com.sun.net.httpserver`, no extra dependency). The tests drive it with the production stack: Ktor Java engine
with `wavesDefaults()`, `DriveCloudStore`, `DefaultSyncService` and `SqlLibraryRepository` on a real SQLite file.

It follows the public Drive v3 reference, not our client:

| Feature | Emulated behaviour |
| --- | --- |
| `files.list` | `spaces` selects the corpus. The default is `drive`, so appDataFolder files are invisible without `spaces=appDataFolder`. `q` supports `name = '…'` (with `\'` and `\\` escapes) and `trashed = …`, joined by `and`. Anything else returns 400. `orderBy` accepts `createdTime`, `modifiedTime` and `name`, each optionally `desc`. `pageSize` runs from 1 to 1000, with `nextPageToken`. `fields` gives a partial response. Without `fields`, a file only has `kind,id,name,mimeType`, so there is no `version`. |
| download | `GET /drive/v3/files/{id}?alt=media` returns the content. Without `alt=media`, it returns metadata. A missing file returns 404 `notFound`. |
| create | `POST /upload/drive/v3/files?uploadType=multipart`, `multipart/related`, with the JSON metadata part first and the media part second. `parents:["appDataFolder"]` puts the file in that space. |
| update | `PATCH /upload/drive/v3/files/{id}?uploadType=media`. The `Content-Type` header becomes the file's `mimeType`. |
| `version` | An int64 sent as a JSON string. Every write increases it by a random 1 to 3, so clients can't assume +1. |
| auth | A missing bearer token, or one passed to `expire()`, returns 401 `authError`. |
| faults | `failNext(status, reason)` returns Google-style error bodies (403 `userRateLimitExceeded`/`storageQuotaExceeded`, 429, 5xx). `dropNext` closes the connection without responding. `beforeHandle` runs code, such as another device's whole sync, before a request is served, to force interleavings. |
| limits | Uploads over 5 MB (the documented limit for simple and multipart uploads) are counted in `oversizedUploads`. |

## Two-device scenarios

`SyncIntegrationTest` runs devices A and B. Each has its own temp SQLite file (opened by `createDesktopDriver`, so
WAL and migrations apply), its own device id and its own token sequence, and both share one fake server and clock.
All tests pass. Timings are per test on the development Mac and include SQLite setup.

| Scenario | Result | Time |
| --- | --- | --- |
| A subscribes and plays to 10 min, syncs; B syncs and shows the subscription and resumes at 10 min | pass | 0.05 s |
| B continues to 20 min while A marks it played (later): both converge to "played". With the order reversed, B's later 25 min wins. A timestamp tie is decided by content (completed wins), never by who synced first | pass | 0.17 s |
| Unsubscribe on B propagates. A's stale "subscribed" record plus unrelated edits don't resurrect it. A later re-subscribe on A does propagate | pass | 0.21 s |
| History from both devices is unioned, including the same episode at the same instant on both (distinct device ids) | pass | 0.10 s |
| Version conflict: B completes a full sync between A's read and A's pre-upload version check. A gets a conflict, re-reads, merges and writes. Both converge | pass | 0.16 s |
| Drive's no-compare-and-set window: B writes between A's version check and A's PATCH, so A overwrites B's file. B's next sync re-uploads its data and nothing is lost (see "Known limitations") | pass | 0.13 s |
| Random concurrency: A and B each do 12 rounds of subscribe, play, history and sync in parallel. After two extra rounds both are identical with every edit present | pass | 0.45 s |
| Network failure: the connection drops during A's PATCH and during B's download. Local DBs and the remote file are untouched (same version, same bytes). The next sync succeeds | pass | 0.25 s |
| Transient 429, 403 `userRateLimitExceeded`, 503 and 500 are retried and the sync succeeds | pass | 0.60 s |
| Persistent 503 fails after 1 + 2 retries. 403 `storageQuotaExceeded` isn't retried. A create that returns 5xx isn't retried. Data is untouched | pass | 0.43 s |
| Expired token: refreshed once and the sync succeeds. If the refreshed token is also rejected, the sync fails with 401 | pass | 0.09 s |
| Corrupt remote file (truncated JSON, wrong types, `"version":"two"`, a JSON array, binary, truncated gzip): status `Failed("Remote sync file is unreadable…")`, nothing written (no POST or PATCH), local untouched | pass | 0.08 s |
| Remote file with `version` = 2 (newer app): `Failed("…Update Waves to keep syncing.")`. The remote bytes, version and local DB are untouched | pass | 0.57 s |
| Unknown top-level keys in a same-version file are kept when the file is rewritten | pass | 0.05 s |
| A plain-JSON file from an earlier build is read, merged and rewritten as gzip | pass | 0.09 s |
| Two simultaneous first syncs create duplicate files. Every device uses the oldest, and they converge | pass | 0.14 s |
| A signed-out device makes no requests | pass | 0.03 s |

### Scale

`SyncScaleTest` covers 500 subscriptions (50 of them tombstones), 20,000 progress rows and 2,000 history entries.
Numbers are from the final run, and the test writes them to `composeApp/build/reports/sync-scale.txt`:

| Step | Time |
| --- | --- |
| A first sync (export, merge, gzip, create) | 128 ms |
| B first sync (download, gunzip, merge, import 22.5k rows) | 361 ms |
| A no-op sync (download, merge, nothing to write or import) | 75 ms |
| B one edit (upload whole file) | 116 ms |
| A pulls that one edit | 75 ms (665 ms before the delta import, see below) |

The file is **4,293,857 bytes as JSON and 204,272 bytes gzipped (21×)**.

**Gzip is warranted, so it's implemented.** Drive documents simple and multipart uploads for files of 5 MB or
less, and this library was already at 4.3 MB. Every sync downloads the whole file, and any change uploads it again,
which costs a lot on mobile data. `DriveCloudStore` now writes `application/gzip`. On read, it detects gzip by its
magic number (`1f 8b`) and otherwise decodes UTF-8, so files from earlier builds still sync. The JSON inside is
unchanged, so this isn't a format-version change. An *older* build reading a gzip file reports "unreadable" and
refuses to overwrite it, which is safe. No build without gzip has been released (app version 1.0.0).

## Bugs found and fixed

1. **A newer app's data could be destroyed.** `DefaultSyncService` decoded the remote file with
   `ignoreUnknownKeys` and wrote the merge back. A version-2 file therefore lost every field version 1 doesn't
   know about, and its `version` stayed 2, so the newer app couldn't even detect the loss. The old unit test
   `toleratesUnknownFieldsFromNewerVersions` was asserting this behaviour. It's fixed by `SyncFormat` (see below).
2. **No retry for transient Drive errors.** One 429, rate-limit 403 or 503 failed the whole sync until the next
   scheduler tick. The Drive error guide asks for exponential backoff, and there's now a 1 s and 4 s backoff (plus
   jitter, or `Retry-After` if longer) for 429, 403 `rateLimitExceeded`/`userRateLimitExceeded` and
   500/502/503/504. A create isn't retried after a 5xx, because the file may exist and a retry could duplicate it.
3. **The sync file was approaching Drive's 5 MB multipart limit** for heavy libraries. Fixed with gzip (above).
4. **Every pull re-imported the whole state.** `importSyncState` ran over all 20k progress rows (one SELECT each)
   even when one record changed, which also invalidated every Flow. Only records that differ from the local
   snapshot are imported now. This is equivalent because the import is a per-key LWW merge.
5. **Database migrations weren't transactional on desktop.** JdbcSqliteDriver's built-in schema handling runs
   `create`/`migrate` outside a transaction. Replaced by `migrateToCurrent` (below).

No merge or convergence bugs were found. `SyncMerge` held up under all the interleavings above.

## Drive API discrepancies (our client vs. the reference)

Checked against developers.google.com/drive/api (files.list, files.update, manage-uploads, appdata, search-files,
handle-errors).

| Item | Status |
| --- | --- |
| `spaces=appDataFolder`, `parents:["appDataFolder"]`, scope `drive.appdata` | Matches the docs. |
| `q` escaping (`\'`, `\\`) and `name = '…' and trashed = false` | Matches. |
| `fields=files(id,name,version)` | Needed. v3 omits `version` by default. Matches. |
| `orderBy=createdTime` | Valid, but the docs warn it can time out on *large* collections. appDataFolder holds one file, so this is fine. |
| Multipart create (JSON metadata first, `multipart/related; boundary=…`, Content-Length) | Matches. The body is now built as bytes so the gzip media part is binary-safe. |
| `PATCH /upload/drive/v3/files/{id}?uploadType=media` | Matches files.update. |
| `version` is an int64 *string* that increases by an unspecified amount | We compare it as an opaque string for equality only. Correct. |
| **No conditional write** (v3 has no If-Match or etag precondition) | A known gap, documented in `DriveCloudStore`. A small race window can overwrite another device's write. It heals on that device's next sync (tested). |
| **Retry and backoff** for 429, 403 rate limits and 5xx | Was missing. Fixed (bug 2). |
| **5 MB** documented size for simple and multipart uploads | We were at 4.3 MB at scale. Fixed with gzip. Resumable upload would be the next step if a compressed file ever got near 5 MB, which would need roughly 400k progress rows. |
| appDataFolder files can't be trashed | We only query `trashed = false`, which is harmless. |
| Android engine `PATCH` | Ktor's Android engine calls `HttpURLConnection.setRequestMethod("PATCH")`. Android's OkHttp-backed implementation accepts PATCH, unlike the JDK's. This can't be verified on the JVM, so it should be checked once on a device with real credentials. |

## Sync file format versioning

The rules are in `composeApp/src/commonMain/kotlin/io/waves/sync/SyncFormat.kt`:

- `SyncState.version` is the format version. This build reads and writes `SyncFormat.SUPPORTED_VERSION` = 1.
- Bump it only for changes an older app can't safely round-trip: new fields *inside* existing records, a changed
  meaning, or a different encoding.
- A build that sees `version` > supported **neither writes nor imports**. The sync status becomes
  `Failed("Your synced library was saved by a newer version of Waves. Update Waves to keep syncing.")`
  (`SyncFormatTooNewException`). The remote file and the local DB stay untouched, and syncing resumes once the
  app is updated.
- Within one version, new data may only be added as new **top-level** keys. Older builds keep unknown top-level
  keys verbatim when they rewrite the file.
- Unparseable files fail with `SyncFileUnreadableException` and are never overwritten.

## Database schema versioning (SQLDelight)

- The `.sq` files are the current schema. `src/commonMain/sqldelight/migrations/<n>.sqm` upgrades version n to
  n + 1. `src/commonMain/sqldelight/databases/<n>.db` snapshots each version, generated with
  `./gradlew :composeApp:generateCommonMainWavesDatabaseSchema`.
- `verifyMigrations = true`: `verifySqlDelightMigration` checks that every snapshot plus the migrations equals the
  current schema.
- **Version 1** is the original schema (podcast, episode, progress, history, setting). **Version 2** adds
  `download` (`1.sqm`). That migration uses `CREATE TABLE IF NOT EXISTS`, because version-1 builds created the
  table lazily at runtime (`CREATE_DOWNLOAD_TABLE` in `KtorDownloadManager`, which is harmless and kept).
- **Android**: `AndroidSqliteDriver(WavesDatabase.Schema, …)` uses SupportSQLiteOpenHelper, which runs
  `onCreate`/`onUpgrade` (→ `Schema.migrate`) in a transaction and stores the version. Nothing more is needed.
- **Desktop**: `createDesktopDriver` uses `migrateToCurrent`, which reads `PRAGMA user_version`. At 0 it runs
  create; below the current version it migrates. Either way it then sets `user_version`, all in one transaction. A
  database from a newer build (higher `user_version`) is left untouched. Connections use `journal_mode=WAL`,
  `synchronous=NORMAL`, `busy_timeout=5000` and `foreign_keys=ON`.
- `SchemaMigrationTest` covers these cases:
  - A v1 database (the `1.db` snapshot with `user_version=1`) holding podcasts, a tombstone, an episode,
    progress, history and the device id migrates to v2, and every row survives.
  - The migrated schema is identical to a freshly created one.
  - A v1 database that already has the runtime-created `download` table migrates and keeps its rows.
  - Reopening a database is a no-op.
  - A newer database is left alone.
  - The PRAGMAs are in effect.

**Adding a table or column later:** change the `.sq` file, add `migrations/<current>.sqm` with the same DDL, run
`generateCommonMainWavesDatabaseSchema` to add the next `<n>.db`, and run `verifySqlDelightMigration`. Adding
queries alone, for example to `Setting.sq`, needs no migration.

## Known limitations

- The Drive compare-and-set window (see above) is inherent to Drive v3. A lost remote write is always still in the
  losing device's database and is re-sent on its next sync.
- Duplicate sync files from simultaneous first syncs are never deleted. Every device reads the oldest, so the extra
  file is harmless clutter.
- History is capped at 2000 entries in the sync file (`SyncMerge.HISTORY_CAP`). Local history beyond that stays
  local.
- `archive` (a top-level key added within version 1) carries the stored episodes of followed YouTube live archives,
  whose feed lists only the newest 15. Capped at the newest 2000 (`SyncMerge.ARCHIVE_CAP`). Import only adds
  episodes a device lacks (or a length it lacks); it never overwrites one it already has.
- `showSettings` (a top-level key added within version 1) carries each show's notification and Home choices, last
  change wins per feed URL. A show without a record notifies and is listed on Home.
- `listening` (a top-level key added within version 1) carries each device's listening time per local day, merged by
  keeping the larger total; History's header sums this week's across devices. Capped at the newest 1000 records
  (`SyncMerge.LISTENING_CAP`).
