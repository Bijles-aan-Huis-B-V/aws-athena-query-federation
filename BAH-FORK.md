# BAH fork notes

This is the Bijles-aan-Huis fork of [awslabs/aws-athena-query-federation](https://github.com/awslabs/aws-athena-query-federation). The upstream `README.md` documents the project itself; this file documents only the **fork-specific** bits — why it exists, what we changed, and how it gets built / deployed.

## Why we forked

We use the `athena-docdb` connector to query our production DocumentDB cluster from Athena. The upstream connector samples **10 documents** per cold start to infer Arrow schemas (the constant `SCHEMA_INFERRENCE_NUM_DOCS` in `DocDBMetadataHandler.java`, no env var override). Because MongoDB is schemaless, that small sample is non-deterministic across cold starts. We periodically hit `TYPE_NOT_FOUND: Unknown type: row` at query-planning time when the sample happened to include sub-documents that were present but empty (`{}`), which the connector then emitted as `struct<>` — a row type with no children that Athena engine v3 rejects.

Both knobs (sample size, and the empty-struct emission) are hardcoded upstream. We considered a daily Glue-sync Lambda or a single-column JSON projection in Glue; both moved the moving parts elsewhere without removing them. A tiny fork was the simpler answer.

## What we changed

Two commits on the [`bah-fork`](https://github.com/Bijles-aan-Huis-B-V/aws-athena-query-federation/tree/bah-fork) branch, both touching `athena-docdb/`:

| Commit | Files | Change |
|---|---|---|
| `docdb: bump schema-inference sample size from 10 to 3000` | `DocDBMetadataHandler.java` | Constant bump. 3000 docs → schema becomes effectively deterministic. Cold-start adds ~2-5 s for the first query against a collection; warm queries unaffected. |
| `docdb: downgrade empty struct fields to VARCHAR` | `SchemaUtils.java` | Root-cause fix: when `getArrowField` sees an empty `Document`, emit VARCHAR instead of empty STRUCT. Plus a defensive walk over the built schema rewriting any residual `struct<>` to VARCHAR. |

No SDK changes, no behaviour change for fields whose sub-shape is discoverable in the sample.

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

The two-patch design is what keeps rebases cheap.

## Build / deploy

CI (`.github/workflows/`) builds and pushes the connector image to our private ECR repo `<old-account-id>.dkr.ecr.eu-central-1.amazonaws.com/athena-prod-mongo-connector-v2`. Two triggers:

| Trigger | Image tag(s) |
|---|---|
| Push to `bah-fork` | `bah-fork-latest` |
| Push to `feature/**` | `bah-fork-feature-<branch>` |
| Push tag `bah-fork-vN` | `bah-fork-vN` **and** `bah-fork-latest` |

**Deployment is manual** and lives in the infra repo, not here. After CI pushes a new image:

```bash
cd infra/infrastructure/staging/eu-central-1/bah-staging/athena-prod-federated-query-v2/lambda-mongo
# Update image_tag in terragrunt.hcl to the tag CI pushed (or rely on bah-fork-latest)
terragrunt apply
```

See `infra/athena-federated-query.md` §10 for the full apply order and rollback procedure.

## Local build

For development/testing without CI:

```bash
# Maven inside Docker (no local JDK required), then a single docker build that
# bakes the resulting JAR into the runtime image. Pushes to ECR.
infra/scripts/build-mongo-connector.sh
```

The script does the same thing CI does; useful when iterating on a patch before pushing.

## Maintenance

- **Upstream tracking**: check the [upstream changelog](https://github.com/awslabs/aws-athena-query-federation/releases) every few months. Rebase only when there's something we want (perf, bug fix, new MongoDB driver). The fork's value is small and stable; no point chasing every release.
- **CI secrets needed**: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` — same credentials other BAH service repos use to push to ECR.
- **Rollback**: revert `lambda-mongo/terragrunt.hcl` to point at the previous image tag and `terragrunt apply`. ECR retains the last 10 tagged images, so any recent tag is recoverable. Worst-case full rollback: `git log -- infra/.../lambda-mongo/main.tf` has the SAR-based main.tf one revert away.

## Contact

Mehmet (Engineering) — the fork was set up 2026-05-22 in response to a `TYPE_NOT_FOUND` outage on the `user.users` collection.
