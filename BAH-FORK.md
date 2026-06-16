# BAH fork notes

This is the Bijles-aan-Huis fork of [awslabs/aws-athena-query-federation](https://github.com/awslabs/aws-athena-query-federation). The upstream `README.md` documents the project itself; this file documents only the **fork-specific** bits — why it exists, what we changed, and how it gets built / deployed.

## Why we forked

We use the `athena-docdb` connector to query our production DocumentDB cluster from Athena. The upstream connector samples **10 documents** per cold start to infer Arrow schemas (the constant `SCHEMA_INFERRENCE_NUM_DOCS` in `DocDBMetadataHandler.java`, no env var override). Because MongoDB is schemaless, that small sample is non-deterministic across cold starts. We periodically hit `TYPE_NOT_FOUND: Unknown type: row` at query-planning time when the sample happened to include sub-documents that were present but empty (`{}`), which the connector then emitted as `struct<>` — a row type with no children that Athena engine v3 rejects.

These are all cases where upstream's defaults don't fit School Talent's data model (schemaless sampling, empty sub-documents, a cursor leak, and numeric `_id`s). We considered a daily Glue-sync Lambda or a single-column JSON projection in Glue for the schema problems; both moved the moving parts elsewhere without removing them. A tiny fork was the simpler answer.

## What we changed

Four commits on the [`bah-fork`](https://github.com/Bijles-aan-Huis-B-V/aws-athena-query-federation/tree/bah-fork) branch, all touching `athena-docdb/`:

| Commit | Files | Change |
|---|---|---|
| `docdb: bump schema-inference sample size from 10 to 3000` | `DocDBMetadataHandler.java` | Constant bump. 3000 docs → schema becomes effectively deterministic. Cold-start adds ~2-5 s for the first query against a collection; warm queries unaffected. |
| `docdb: downgrade empty struct fields to VARCHAR` | `SchemaUtils.java` | Root-cause fix: when `getArrowField` sees an empty `Document`, emit VARCHAR instead of empty STRUCT. Plus a defensive walk over the built schema rewriting any residual `struct<>` to VARCHAR. |
| `docdb: close the Mongo cursor after each read` | `DocDBRecordHandler.java` | Wrap the query cursor in try-with-resources so it is closed when the query finishes. Upstream leaks the cursor and relies on the server-side ~10-min timeout to reclaim it; on a t3.medium DocumentDB instance (hard limit of **30** open cursors) a burst of analytics queries exhausts the limit and the engine rejects new queries with `Cannot open a new cursor since too many cursors are already opened`. |
| `docdb: only coerce _id filter values to ObjectId when actually an ObjectId` | `QueryUtils.java` | Upstream wraps **every** `_id` filter value in `new ObjectId(...)`, assuming all `_id`s are 24-char hex ObjectIds. Our app writes numeric (Long) ids into `_id`, so `WHERE _id = 1514` threw `invalid hexadecimal representation of an ObjectId: [1514]`. Added `coerceIdValue()` — convert to ObjectId only when `ObjectId.isValid()` is true, else pass the raw value through. Applied at all five `_id` conversion sites (eq, IN, NOT_IN, plan-based eq, plan-based $in/$nin). Reading `_id` always worked (surfaces as bigint); only filter pushdown was broken. |

No SDK changes, no behaviour change for fields whose sub-shape is discoverable in the sample.

### Connector-adjacent fixes that live outside this fork

Two related issues were fixed in infrastructure config, not in connector code (documented here so the full picture is in one place):

- **Connection-pool bounds** — the Lambda's DocumentDB connection string carries `maxPoolSize=10&maxIdleTimeMS=60000&maxConnecting=2&waitQueueTimeoutMS=10000` so idle connections (and their reserved cursor slots) are released promptly instead of lingering across warm invocations.
- **Reserved concurrency = 12** — Athena fans one federated query into many parallel split invocations (observed 47-77 at once), each opening a cursor; capping concurrency keeps worst-case concurrent cursors under the t3.medium limit of 30.

Both live in `infra/.../athena-prod-federated-query-v2/lambda-mongo/`. If the DocumentDB instance is ever scaled up (e.g. r5.large = 450 cursors), the concurrency cap can be raised or removed.

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
# repo at <old-account-id>.dkr.ecr.eu-central-1.amazonaws.com/athena-prod-mongo-connector-v2.
infra/scripts/build-mongo-connector.sh

# Override the image tag (defaults to bah-fork-v1):
IMAGE_TAG=bah-fork-v2 infra/scripts/build-mongo-connector.sh
```

We dropped GitHub Actions for this fork — the connector image is rebuilt only when one of the two patches changes, which is rare enough that maintaining org-wide CI secrets for a public fork wasn't worth the security exposure.

After pushing a new image, deploy via terragrunt in the infra repo:

```bash
cd infra/infrastructure/staging/eu-central-1/bah-staging/athena-prod-federated-query-v2/lambda-mongo
# If you bumped the image tag, update image_tag in terragrunt.hcl first.
terragrunt apply
```

See `infra/athena-federated-query.md` §10 for the full apply order and rollback procedure.

## Maintenance

- **Upstream tracking**: check the [upstream changelog](https://github.com/awslabs/aws-athena-query-federation/releases) every few months. Rebase only when there's something we want (perf, bug fix, new MongoDB driver). The fork's value is small and stable; no point chasing every release.
- **Build credentials**: `infra/scripts/build-mongo-connector.sh` uses your local AWS CLI session to push to ECR. No GitHub secrets needed.
- **Rollback**: revert `lambda-mongo/terragrunt.hcl` to point at the previous image tag and `terragrunt apply`. ECR retains the last 10 tagged images, so any recent tag is recoverable. Worst-case full rollback: `git log -- infra/.../lambda-mongo/main.tf` has the SAR-based main.tf one revert away.

## Contact

Mehmet (Engineering) — the fork was set up 2026-05-22 in response to a `TYPE_NOT_FOUND` outage on the `user.users` collection.
