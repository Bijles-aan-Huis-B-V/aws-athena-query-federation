#!/usr/bin/env bash
#
# Builds and pushes the BAH-forked Athena DocumentDB connector image to ECR.
#
# Lives in this repo because it builds this repo; the connector image is the only artefact the fork
# produces. There is no CI here, by design — see BAH-FORK.md.
#
# Prerequisites:
#   - Docker daemon running (used both for the Maven build and the connector image).
#     No local JDK or Maven needed; both run in containers.
#   - AWS CLI authenticated against the production account <account-id> (eu-central-1) with
#     permission to push to the ECR repo athena-prod-mongo-connector-v2. Use AWS_PROFILE=bah-prod.
#   - The ecr/athena-prod-mongo-connector-v2 terragrunt unit must already be applied.
#     Tags in that repo are immutable, so a tag can never be rebuilt in place — bump IMAGE_TAG.
#
# What it does:
#   1. Runs Maven inside a maven:3.9-eclipse-temurin-11 container to build the
#      athena-docdb JAR (skips tests, reuses the host ~/.m2 cache).
#   2. Builds the connector Docker image from athena-docdb/Dockerfile.
#   3. Logs in to ECR, tags the image, pushes.
#
# Usage:
#   AWS_PROFILE=bah-prod ./build-connector.sh                     # tag: bah-fork-v6
#   AWS_PROFILE=bah-prod IMAGE_TAG=bah-fork-v7 ./build-connector.sh
#
# Afterwards, in the infra repo, set the new tag in the image_uri of
# infrastructure/production/eu-central-1/bah-production/athena/connectors/mongo-v2 and apply. Then tag
# the commit here (git tag -a bah-fork-vN) with the image digest in the message, so the running image
# can always be traced back to source.
#
set -euo pipefail

# ---- config -----------------------------------------------------------------

AWS_REGION="${AWS_REGION:-eu-central-1}"
AWS_ACCOUNT_ID="${AWS_ACCOUNT_ID:-<account-id>}"
ECR_REPO_NAME="${ECR_REPO_NAME:-athena-prod-mongo-connector-v2}"
IMAGE_TAG="${IMAGE_TAG:-bah-fork-v6}"

# The script sits at the root of the fork, so the source is simply its own directory.
FORK_PATH="${FORK_PATH:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"

if [[ ! -d "${FORK_PATH}/athena-docdb" ]]; then
  echo "error: athena-docdb not found under ${FORK_PATH}" >&2
  echo "       run this from the fork checkout, or set FORK_PATH explicitly." >&2
  exit 1
fi

ECR_REPO_URI="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_REPO_NAME}"

echo "==> fork path:      ${FORK_PATH}"
echo "==> ECR repo URI:   ${ECR_REPO_URI}"
echo "==> image tag:      ${IMAGE_TAG}"
echo

# ---- 1. Maven build ---------------------------------------------------------

echo "==> [1/3] Maven build (athena-docdb only, skip tests)"
docker run --rm \
  -v "${FORK_PATH}":/build \
  -w /build \
  -v "${HOME}/.m2":/root/.m2 \
  maven:3.9-eclipse-temurin-11 \
  mvn clean install -pl athena-docdb -am -DskipTests -B

# Sanity check: the shaded JAR should now exist.
JAR_PATH="${FORK_PATH}/athena-docdb/target/athena-docdb-2026.17.1.jar"
if [[ ! -f "${JAR_PATH}" ]]; then
  echo "error: expected JAR not produced at ${JAR_PATH}" >&2
  exit 1
fi
echo "    built $(du -h "${JAR_PATH}" | cut -f1) JAR at ${JAR_PATH}"

# ---- 2. Docker image build --------------------------------------------------

echo
echo "==> [2/3] Docker image build"
cd "${FORK_PATH}/athena-docdb"
# --provenance=false + --sbom=false: AWS Lambda rejects images whose ECR manifest
# is the multi-arch/attestation OCI format that modern docker buildx emits by
# default. These flags force a single classic Docker v2 manifest, which Lambda's
# CreateFunction accepts.
docker buildx build --platform linux/amd64 --provenance=false --sbom=false --load \
  -t "${ECR_REPO_NAME}:${IMAGE_TAG}" .

# ---- 3. ECR push ------------------------------------------------------------

echo
echo "==> [3/3] ECR login + push"
aws ecr get-login-password --region "${AWS_REGION}" \
  | docker login --username AWS --password-stdin "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"

docker tag  "${ECR_REPO_NAME}:${IMAGE_TAG}" "${ECR_REPO_URI}:${IMAGE_TAG}"
docker push "${ECR_REPO_URI}:${IMAGE_TAG}"

echo
echo "==> done. image: ${ECR_REPO_URI}:${IMAGE_TAG}"
echo "    next: bump the tag in the infra repo's athena/connectors/mongo-v2 and apply"
