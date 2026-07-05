#!/usr/bin/env bash
#
# One-command local DEV setup for a TLS-encrypted, X.509-authenticated MongoDB.
#
#   ./deploy/x509/setup-dev.sh
#
# Steps (all idempotent):
#   1. generate the dev PKI (CA, server cert, app client cert, JVM keystores)
#   2. (re)start the dev MongoDB with requireTLS + --auth (docker-compose.dev.yml)
#   3. create the X.509 user in $external (mapped to the app cert's subject DN)
#      via the localhost exception
#   4. print how to run the Spring app with the dev profile
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE="docker compose -f $SERVER_DIR/docker-compose.dev.yml"

# Use `docker exec` (not `docker compose exec`) so Compose does not try to
# interpolate the `$external` auth DB in the mongosh script.
run_mongosh() { # runs mongosh inside the mongo container, presenting the client cert
  local cid; cid="$($COMPOSE ps -q mongo)"
  docker exec -i "$cid" mongosh --quiet \
    --tls --tlsCAFile /etc/mongo/certs/ca.crt \
    --tlsCertificateKeyFile /etc/mongo/certs/hinata-app.pem \
    --host localhost "$@"
}

echo "== 1/4  Generating dev X.509 PKI =="
bash "$SCRIPT_DIR/generate-certs.sh" dev

echo "== 2/4  Starting dev MongoDB (TLS + auth) =="
$COMPOSE up -d mongo

echo "== 3/4  Waiting for mongod to accept TLS connections =="
for i in $(seq 1 30); do
  if run_mongosh --eval 'db.runCommand({ping:1}).ok' >/dev/null 2>&1; then break; fi
  sleep 2
  [ "$i" = "30" ] && { echo "mongod did not become ready" >&2; $COMPOSE logs --tail=40 mongo; exit 1; }
done

echo "== 3/4  Ensuring the X.509 \$external user exists =="
DN="$(cat "$SCRIPT_DIR/dev/app-subject-dn.txt")"
CID="$($COMPOSE ps -q mongo)"
BOOT_USER="hinata-bootstrap"
BOOT_PW="${HINATA_MONGO_BOOTSTRAP_PASSWORD:-hinata-dev-bootstrap}"

# The localhost exception only lets you create the FIRST user in the admin db,
# not directly in $external. So: (1) create a bootstrap admin via the exception,
# then (2) authenticate as that admin to create the X.509 user in $external.
# Both steps tolerate "already exists" so the script is idempotent.
docker exec -i "$CID" mongosh --quiet \
  --tls --tlsCAFile /etc/mongo/certs/ca.crt \
  --tlsCertificateKeyFile /etc/mongo/certs/hinata-app.pem --host localhost --eval "
    try { db.getSiblingDB('admin').runCommand({ createUser: '$BOOT_USER', pwd: '$BOOT_PW',
            roles: [{ role: 'userAdminAnyDatabase', db: 'admin' }] });
          print('Created bootstrap admin'); }
    catch (e) { print('bootstrap admin: ' + (/already exists/.test(e.errmsg) ? 'already exists' : e.codeName)); }
  " 2>/dev/null || true

docker exec -i "$CID" mongosh --quiet -u "$BOOT_USER" -p "$BOOT_PW" --authenticationDatabase admin \
  --tls --tlsCAFile /etc/mongo/certs/ca.crt \
  --tlsCertificateKeyFile /etc/mongo/certs/hinata-app.pem --host localhost --eval "
    try { db.getSiblingDB('\$external').runCommand({ createUser: '$DN', roles: [
            { role: 'readWrite', db: 'hinata' }, { role: 'dbAdmin', db: 'hinata' } ]});
          print('Created X.509 user: $DN'); }
    catch (e) { print('X.509 user: ' + (/already exists/.test(e.errmsg) ? 'already exists' : e.errmsg)); }
  " 2>/dev/null || true

echo "== 3/4  Verifying X.509 login works =="
docker exec -i "$CID" mongosh --quiet \
  --tls --tlsCAFile /etc/mongo/certs/ca.crt \
  --tlsCertificateKeyFile /etc/mongo/certs/hinata-app.pem --host localhost \
  --authenticationMechanism MONGODB-X509 --authenticationDatabase '$external' \
  --eval "print('X.509 auth OK as ' + db.runCommand({connectionStatus:1}).authInfo.authenticatedUsers[0].user)" 2>/dev/null

echo ""
echo "== 4/4  Done. Start the server with the dev profile: =="
echo "   SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun"
echo "   (application-dev.yml already points at the TLS/X.509 connection)"
