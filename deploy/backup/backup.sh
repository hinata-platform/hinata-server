#!/bin/sh
# Nightly backup of one hinata stack into a restic repository on another machine (IT-117).
#
# Runs inside the scheduler container (docker CLI + the host socket) and starts short-lived
# helpers on the stack network, so nothing here needs a port published:
#
#   1. the database: mongodump over TLS as an X.509 user with nothing but read on the database,
#      streamed straight into restic, never written to disk;
#   2. the files: rclone copies the bucket with a read-only SeaweedFS identity into a staging
#      directory (root only, kept between runs so only changes move), restic backs that up.
#
# restic encrypts everything before it leaves the host. The repository's REST server is
# append-only: this host can add snapshots but cannot change or delete one; retention runs on
# the backup machine. The outcome of every run is written to last-run.json and backup.log.
#
# Configuration (backup.env, mode 600): STACK, NETWORK, RESTIC_REPOSITORY, RESTIC_PASSWORD,
# S3_BUCKET, S3_BACKUP_ACCESS_KEY, S3_BACKUP_SECRET_KEY, MONGO_HOSTS, MONGO_DB.
# Files beside it: rest-server.crt (pinned TLS certificate), ca.crt and hinata-backup.pem (Mongo).
set -eu
# A failing dump in front of restic must fail the run, not leave an empty snapshot behind.
set -o pipefail

DIR=${BACKUP_DIR:-/opt/stacks/hinata/backup}
cd /backup
set -a; . ./backup.env; set +a

RESTIC_IMAGE=restic/restic:0.19.1
MONGO_IMAGE=mongo:8.0
RCLONE_IMAGE=rclone/rclone:1.71
STARTED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
LOG=/backup/backup.log

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $*" | tee -a "$LOG"; }

restic_() {
  docker run --rm -i --network host \
    -e RESTIC_REPOSITORY -e RESTIC_PASSWORD -e RESTIC_CACERT=/backup/rest-server.crt \
    -e RESTIC_HOST="$STACK" -v "$DIR:/backup" "$RESTIC_IMAGE" "$@"
}

finish() {
  code=$?
  printf '{"stack":"%s","started":"%s","finished":"%s","exitCode":%s,"mongoSnapshot":"%s","filesSnapshot":"%s","files":"%s"}\n' \
    "$STACK" "$STARTED" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$code" "${MONGO_SNAP:-}" "${FILES_SNAP:-}" "${FILES_STATS:-}" \
    > /backup/last-run.json
  [ "$code" = 0 ] && log "backup done" || log "backup FAILED (exit $code)"
  tail -n 2000 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
}
trap finish EXIT

log "backup of $STACK started"
restic_ cat config >/dev/null 2>&1 || { log "initialising the repository"; restic_ init >/dev/null; }

# 1. Database, streamed: dump → restic, never on disk.
log "database"
URI="mongodb://$MONGO_HOSTS/$MONGO_DB?replicaSet=rs0&tls=true&tlsCAFile=/certs/ca.crt&tlsCertificateKeyFile=/certs/hinata-backup.pem&authMechanism=MONGODB-X509&authSource=%24external&readPreference=secondaryPreferred"
docker run --rm --network "$NETWORK" -v "$DIR/ca.crt:/certs/ca.crt:ro" -v "$DIR/hinata-backup.pem:/certs/hinata-backup.pem:ro" \
  "$MONGO_IMAGE" mongodump --uri "$URI" --archive --gzip --quiet \
  | restic_ backup --stdin --stdin-filename "$STACK-mongo.archive.gz" --tag mongo --json \
  | tail -n1 > /backup/mongo-summary.json
MONGO_SNAP=$(sed -n 's/.*"snapshot_id":"\([0-9a-f]*\)".*/\1/p' /backup/mongo-summary.json | cut -c1-8)
MONGO_BYTES=$(sed -n 's/.*"total_bytes_processed":\([0-9]*\).*/\1/p' /backup/mongo-summary.json)
[ -n "$MONGO_SNAP" ] || { log "database snapshot missing"; exit 1; }
# A gzip archive of even an empty database is bigger than this; less means the dump failed.
[ "${MONGO_BYTES:-0}" -gt 1024 ] || { log "database dump empty ($MONGO_BYTES bytes)"; exit 1; }
log "database snapshot $MONGO_SNAP ($MONGO_BYTES bytes)"

# 2. Files: read-only copy of the bucket, then a restic snapshot of it.
log "files"
mkdir -p /backup/staging/files
chmod 700 /backup/staging
docker run --rm --network "$NETWORK" -v "$DIR/staging/files:/files" \
  -e RCLONE_CONFIG_SRC_TYPE=s3 -e RCLONE_CONFIG_SRC_PROVIDER=SeaweedFS \
  -e RCLONE_CONFIG_SRC_ENDPOINT=http://seaweedfs:8333 \
  -e RCLONE_CONFIG_SRC_ACCESS_KEY_ID="$S3_BACKUP_ACCESS_KEY" \
  -e RCLONE_CONFIG_SRC_SECRET_ACCESS_KEY="$S3_BACKUP_SECRET_KEY" \
  "$RCLONE_IMAGE" sync "src:$S3_BUCKET" /files --checksum --transfers 8 --quiet
FILES_STATS=$(docker run --rm -v "$DIR/staging/files:/files:ro" alpine:3.22 sh -c 'echo "$(find /files -type f | wc -l) files, $(find /files -type f -exec cat {} + | wc -c) bytes"')
log "staged: $FILES_STATS"
restic_ backup /backup/staging/files --tag files --json | tail -n1 > /backup/files-summary.json
FILES_SNAP=$(sed -n 's/.*"snapshot_id":"\([0-9a-f]*\)".*/\1/p' /backup/files-summary.json | cut -c1-8)
[ -n "$FILES_SNAP" ] || { log "files snapshot missing"; exit 1; }
log "files snapshot $FILES_SNAP"
