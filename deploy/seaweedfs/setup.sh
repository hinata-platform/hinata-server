#!/bin/sh
# One-shot setup for hinata's bucket: creates it if missing and makes SSE-S3 its default, so an
# object written without the header (by any client) is encrypted too. hinata sends the header
# itself as well (HINATA_S3_SSE=AES256). Idempotent; runs with the admin identity.
set -eu
BUCKET=${HINATA_S3_BUCKET:-hinata}
export AWS_ACCESS_KEY_ID="$SEAWEEDFS_ADMIN_ACCESS_KEY"
export AWS_SECRET_ACCESS_KEY="$SEAWEEDFS_ADMIN_SECRET_KEY"
export AWS_DEFAULT_REGION=us-east-1
S3="aws --endpoint-url ${SEAWEEDFS_ENDPOINT:-http://seaweedfs:8333} s3api"

for attempt in $(seq 1 60); do
  $S3 list-buckets >/dev/null 2>&1 && break
  sleep 2
done
$S3 head-bucket --bucket "$BUCKET" >/dev/null 2>&1 || $S3 create-bucket --bucket "$BUCKET"
$S3 put-bucket-encryption --bucket "$BUCKET" --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
$S3 get-bucket-encryption --bucket "$BUCKET"
echo "seaweedfs-setup: bucket $BUCKET ready"
