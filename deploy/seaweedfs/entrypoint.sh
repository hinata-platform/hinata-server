#!/bin/sh
# Starts SeaweedFS as hinata's S3 store, with every protection it offers turned on.
#
# - S3 only with credentials: SeaweedFS lets anyone in while no identity is configured, so this
#   refuses to start without them. Two identities: hinata's, limited to its one bucket, and an
#   admin one that only the setup job uses.
# - Encryption at rest: SSE-S3 under WEED_S3_SSE_KEK, which lives in the environment and never on
#   the data volume. hinata asks for it on every write and copy (HINATA_S3_SSE=AES256), because
#   SeaweedFS 4.48 accepts a bucket default encryption and does not apply it.
#   -filer.encryptVolumeData is deliberately off: in 4.48 an object copied with it on cannot be
#   read back, and its chunk keys sit in the filer store on the same volume anyway.
# - Volume and filer requests between the components are signed (WEED_JWT_* from the environment).
# - Only the S3 port is meant to leave the container; master, volume and filer stay inside.
set -eu

for name in HINATA_S3_ACCESS_KEY HINATA_S3_SECRET_KEY SEAWEEDFS_ADMIN_ACCESS_KEY \
    SEAWEEDFS_ADMIN_SECRET_KEY WEED_S3_SSE_KEK WEED_JWT_SIGNING_KEY WEED_JWT_SIGNING_READ_KEY \
    WEED_JWT_FILER_SIGNING_KEY WEED_JWT_FILER_SIGNING_READ_KEY; do
  eval "value=\${$name:-}"
  if [ -z "$value" ]; then
    echo "seaweedfs: $name is not set, refusing to start" >&2
    exit 1
  fi
done

BUCKET=${HINATA_S3_BUCKET:-hinata}
mkdir -p /etc/seaweedfs
umask 077
cat > /etc/seaweedfs/s3.json <<JSON
{
  "identities": [
    {
      "name": "hinata",
      "credentials": [{"accessKey": "$HINATA_S3_ACCESS_KEY", "secretKey": "$HINATA_S3_SECRET_KEY"}],
      "actions": ["Read:$BUCKET", "Write:$BUCKET", "List:$BUCKET", "Tagging:$BUCKET"]
    },
    {
      "name": "admin",
      "credentials": [{"accessKey": "$SEAWEEDFS_ADMIN_ACCESS_KEY", "secretKey": "$SEAWEEDFS_ADMIN_SECRET_KEY"}],
      "actions": ["Admin", "Read", "Write", "List", "Tagging"]
    }
  ]
}
JSON

exec weed server \
  -dir=/data \
  -ip=127.0.0.1 \
  -ip.bind=0.0.0.0 \
  -master.volumeSizeLimitMB=1024 \
  -volume.max=0 \
  -filer \
  -s3 \
  -s3.port=8333 \
  -s3.config=/etc/seaweedfs/s3.json \
  "$@"
