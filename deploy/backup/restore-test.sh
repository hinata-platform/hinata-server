#!/bin/sh
# Proves the latest backup can be restored (IT-117), without touching the running stack.
#
#   database  the latest mongo snapshot is restored into a throwaway MongoDB on a throwaway
#             network; its collection and document counts are set beside the live ones.
#   files     the latest files snapshot is restored into a throwaway directory; its file count and
#             bytes are set beside the bucket's.
#
# Prints counts only, never a value, a key or a document. Everything temporary is removed.
set -eu
# A failing dump in front of restic must fail the run, not leave an empty snapshot behind.
set -o pipefail
DIR=${BACKUP_DIR:-/opt/stacks/hinata/backup}
cd /backup
set -a; . ./backup.env; set +a
RESTIC_IMAGE=restic/restic:0.19.1
MONGO_IMAGE=mongo:8.0
TMPNET=hinata-restore-test
TMPDB=hinata-restore-test-mongo

restic_() {
  docker run --rm -i --network host \
    -e RESTIC_REPOSITORY -e RESTIC_PASSWORD -e RESTIC_CACERT=/backup/rest-server.crt \
    -v "$DIR:/backup" "$RESTIC_IMAGE" "$@"
}
cleanup() {
  docker rm -f "$TMPDB" >/dev/null 2>&1 || true
  docker network rm "$TMPNET" >/dev/null 2>&1 || true
  rm -rf /backup/restore-test
}
trap cleanup EXIT

COUNT_JS='let c=0,d=0; db.getCollectionNames().forEach(n=>{c++; d+=db.getCollection(n).countDocuments({});}); print(c+" collections, "+d+" documents")'

echo "== snapshots"
restic_ snapshots --compact --latest 2 | tail -n +1

echo "== database"
docker network create "$TMPNET" >/dev/null
docker run -d --name "$TMPDB" --network "$TMPNET" "$MONGO_IMAGE" --quiet >/dev/null
for i in $(seq 1 30); do docker exec "$TMPDB" mongosh --quiet --eval 1 >/dev/null 2>&1 && break; sleep 2; done
restic_ dump latest --tag mongo "/$STACK-mongo.archive.gz" \
  | docker exec -i "$TMPDB" mongorestore --archive --gzip --quiet
echo "restored: $(docker exec "$TMPDB" mongosh --quiet "$MONGO_DB" --eval "$COUNT_JS")"
URI="mongodb://$MONGO_HOSTS/$MONGO_DB?replicaSet=rs0&tls=true&tlsCAFile=/certs/ca.crt&tlsCertificateKeyFile=/certs/hinata-backup.pem&authMechanism=MONGODB-X509&authSource=%24external&readPreference=secondaryPreferred"
echo "live:     $(docker run --rm --user 0:0 --network "$NETWORK" -v "$DIR/ca.crt:/certs/ca.crt:ro" -v "$DIR/hinata-backup.pem:/certs/hinata-backup.pem:ro" \
  "$MONGO_IMAGE" mongosh --quiet "$URI" --eval "$COUNT_JS")"

echo "== files"
mkdir -p /backup/restore-test && chmod 700 /backup/restore-test
restic_ restore latest --tag files --target /backup/restore-test --quiet
docker run --rm -v "$DIR/restore-test:/r:ro" -v "$DIR/staging/files:/s:ro" alpine:3.22 sh -c '
  echo "restored: $(find /r -type f | wc -l) files, $(find /r -type f -exec cat {} + | wc -c) bytes"
  echo "staged:   $(find /s -type f | wc -l) files, $(find /s -type f -exec cat {} + | wc -c) bytes"'
echo "restore test done"
