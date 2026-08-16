# BAH fork notes

This is the Bijles-aan-Huis fork of [awslabs/aws-athena-query-federation](https://github.com/awslabs/aws-athena-query-federation). The upstream `README.md` documents the project itself; this file documents only the **fork-specific** bits — why it exists, what we changed, and how it gets built / deployed.

## Why we forked

We use the `athena-docdb` connector to query our production DocumentDB cluster from Athena. The upstream connector samples **10 documents** per cold start to infer Arrow schemas (the constant `SCHEMA_INFERRENCE_NUM_DOCS` in `DocDBMetadataHandler.java`, no env var override). Because MongoDB is schemaless, that small sample is non-deterministic across cold starts. We periodically hit `TYPE_NOT_FOUND: Unknown type: row` at query-planning time when the sample happened to include sub-documents that were present but empty (`{}`), which the connector then emitted as `struct<>` — a row type with no children that Athena engine v3 rejects.

These are all cases where upstream's defaults don't fit School Talent's data model (schemaless sampling, empty sub-documents, a cursor leak, and numeric `_id`s). We considered a daily Glue-sync Lambda or a single-column JSON projection in Glue for the schema problems; both moved the moving parts elsewhere without removing them. A tiny fork was the simpler answer.

## What we changed

Seven commits on the [`bah-fork`](https://github.com/Bijles-aan-Huis-B-V/aws-athena-query-federation/tree/bah-fork) branch, all touching `athena-docdb/`:

| Commit | Files | Change |
|---|---|---|
| `docdb: bump schema-inference sample size from 10 to 3000` | `DocDBMetadataHandler.java` | Constant bump. 3000 docs → schema becomes effectively deterministic. Cold-start adds ~2-5 s for the first query against a collection; warm queries unaffected. |
| `docdb: downgrade empty struct fields to VARCHAR` | `SchemaUtils.java` | Root-cause fix: when `getArrowField` sees an empty `Document`, emit VARCHAR instead of empty STRUCT. Plus a defensive walk over the built schema rewriting any residual `struct<>` to VARCHAR. |
| `docdb: close the Mongo cursor after each read` | `DocDBRecordHandler.java` | Wrap the query cursor in try-with-resources so it is closed when the query finishes. Upstream leaks the cursor and relies on the server-side ~10-min timeout to reclaim it; on a t3.medium DocumentDB instance (hard limit of **30** open cursors) a burst of analytics queries exhausts the limit and the engine rejects new queries with `Cannot open a new cursor since too many cursors are already opened`. |
| `docdb: only coerce _id filter values to ObjectId when actually an ObjectId` | `QueryUtils.java` | Upstream wraps **every** `_id` filter value in `new ObjectId(...)`, assuming all `_id`s are 24-char hex ObjectIds. Our app writes numeric (Long) ids into `_id`, so `WHERE _id = 1514` threw `invalid hexadecimal representation of an ObjectId: [1514]`. Added `coerceIdValue()` — convert to ObjectId only when `ObjectId.isValid()` is true, else pass the raw value through. Applied at all five `_id` conversion sites (eq, IN, NOT_IN, plan-based eq, plan-based $in/$nin). Reading `_id` always worked (surfaces as bigint); only filter pushdown was broken. |
| `docdb: keep empty sub-documents as empty STRUCT, not VARCHAR` | `SchemaUtils.java` | Narrows the patch above. Downgrading *every* empty `Document` to VARCHAR also flattened sub-documents that are merely empty in the sampled rows but populated elsewhere, so those columns became unqueryable strings. Empty sub-documents now stay STRUCT and only genuinely childless structs are rewritten at the end. |
| `docdb: keep arrays-of-sub-documents as LIST<STRUCT>, not LIST<VARCHAR>` | `SchemaUtils.java` | Same class of problem one level down: an array whose first sampled element was an empty document produced `array<varchar>`, hiding every field of the element type. |
| `docdb: sample recently-updated documents as well, and make sampling configurable` | `SchemaUtils.java`, `DocDBMetadataHandler.java` | The base sample is an unsorted `find().limit(n)`: stable across cold starts, but it only ever reaches one fixed region of the collection, so a recently added field populated on a few active documents never appears (a recently added field existed on 9 of ~100k users and was missing). Adds a second slice over the most recently updated documents, unioned with the base slice, and moves the sizes into the environment — see below. |

No SDK changes, no behaviour change for fields whose sub-shape is discoverable in the sample.

### Sampling configuration

Set on the connector Lambda's environment (in the infra repo, `athena/connectors/mongo-v2`). Defaults
reproduce the pre-patch behaviour, so an unconfigured deployment behaves exactly as before.

| Variable | Default | Meaning |
|---|---|---|
| `docdb_sample_base` | `3000` | Documents read in the unsorted base slice. Coverage here is *positional* and therefore permanent: once a document falls inside the window it stays inside it, so this is the lever for making a rare field permanently visible. |
| `docdb_sample_recent` | `0` (off) | Documents read in the recency slice, sorted by `docdb_recency_field` descending. |
| `docdb_recency_field` | unset | Field to sort the recency slice on, typically `updated_at`. |

The recency slice is skipped unless the collection has an index whose **first key** is that field. That
guard is deliberate: `user_searches` holds 1.6M documents and has no `updated_at`, and sorting it
unindexed would push DocumentDB into an in-memory sort. It also makes the feature opt-in per collection
without any per-collection configuration — if you want recency sampling somewhere, index the field.

Production currently runs `3000` base + `2000` recent on `updated_at`. Against a collection of ~100k documents,
seeing a few hundred updates a day, the recency slice reaches back roughly four days. Note the
consequence: a field carried by only a few documents drops out of the schema once none of them has been
touched for longer than that window, and returns when one is. If a column needs to be permanently
present, raise `docdb_sample_base` rather than the recency size.

### Connector-adjacent fixes that live outside this fork

Two related issues were fixed in infrastructure config, not in connector code (documented here so the full picture is in one place):

- **Connection-pool bounds** — the Lambda's DocumentDB connection string carries `maxPoolSize=10&maxIdleTimeMS=60000&maxConnecting=2&waitQueueTimeoutMS=10000` so idle connections (and their reserved cursor slots) are released promptly instead of lingering across warm invocations.
- **Reserved concurrency = 12** — Athena fans one federated query into many parallel split invocations (observed 47-77 at once), each opening a cursor; capping concurrency keeps worst-case concurrent cursors under the t3.medium limit of 30.

Both live in `infra/infrastructure/production/eu-central-1/bah-production/athena/connectors/mongo-v2/`. The production DocumentDB is still `db.t3.medium`, so the cap still applies; if it is ever scaled up (e.g. `r5.large` = 450 cursors) the cap can be raised or removed.

## Branch model

- `master` — tracks upstream `awslabs/aws-athena-query-federation`. Never modify; only update via `git pull upstream master`.
- `bah-fork` — our changes. Always rebased on top of the upstream release tag we depend on (current: `v2026.17.1`).

To bump the upstream version:

```bash
git fetch upstream
git checkout bah-fork
git rebase v2026.NEW.X
# resolve any conflicts (our patches almost never touch upstream-changed lines)
git push origin bah-fork --force-with-lease
```

The small, self-contained-patch design is what keeps rebases cheap.

## Build / deploy

**No CI in this fork.** Builds are run manually from a developer machine using the script in the infra repo:

```bash
# Maven inside Docker (no local JDK required), then a single docker build that
# bakes the resulting JAR into the runtime image, and pushes to our private ECR
# repo at <account-id>.dkr.ecr.eu-central-1.amazonaws.com/athena-prod-mongo-connector-v2.
infra/scripts/build-mongo-connector.sh

# Override the image tag:
IMAGE_TAG=bah-fork-v7 infra/scripts/build-mongo-connector.sh
```

We dropped GitHub Actions for this fork — the connector image is rebuilt only when one of the patches changes, which is rare enough that maintaining org-wide CI secrets for a public fork wasn't worth the security exposure.

Tags in that ECR repository are **immutable**, so an existing tag cannot be rebuilt in place; bump `IMAGE_TAG` and tag the matching commit here (`git tag -a bah-fork-vN <commit>`) with the resulting image digest in the message. Old prod skipped that step once and the commit behind a running image had to be recovered from build timestamps.

After pushing a new image, deploy via terragrunt in the infra repo:

```bash
cd infra/infrastructure/production/eu-central-1/bah-production/athena/connectors/mongo-v2
# Update the tag in image_uri first.
AWS_PROFILE=bah-prod terragrunt apply
```

The unit uses the local `modules/athena-connector-image` module — a plain container-image Lambda plus its Athena data catalog registration. The stock SAR module (`modules/athena-connector`) cannot deploy a custom image and is what the MySQL connector still uses.

## Maintenance

- **Upstream tracking**: check the [upstream changelog](https://github.com/awslabs/aws-athena-query-federation/releases) every few months. Rebase only when there's something we want (perf, bug fix, new MongoDB driver). The fork's value is small and stable; no point chasing every release.
- **Build credentials**: `infra/scripts/build-mongo-connector.sh` uses your local AWS CLI session to push to ECR. No GitHub secrets needed.
- **Rollback**: point `image_uri` at the previous tag and apply. ECR keeps the last 10 images, so any recent tag is recoverable. Sampling changes need no rebuild at all — the sizes are environment variables, so a slow cold start can be tuned down by editing the unit and applying.

## Contact

Mehmet (Engineering) — the fork was set up 2026-05-22 in response to a `TYPE_NOT_FOUND` outage on the `user.users` collection.
