#!/usr/bin/env bash
#
# Registers the application's X.509 client certificate as a MongoDB user in
# $external on the PROD replica set. Run once after the cluster is up:
#
#   ./deploy/x509/generate-certs.sh prod         # creates the PKI (if not present)
#   docker compose up -d mongo1 mongo2 mongo-arbiter
#   ./deploy/x509/init-prod-user.sh              # creates the X.509 user
#   docker compose up -d hivora-server
#
# Uses the SCRAM root account (MONGO_ROOT_USERNAME/PASSWORD from .env) over TLS.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE="docker compose -f $SERVER_DIR/docker-compose.yml"

# Load MONGO_ROOT_* from .env
set -a; [ -f "$SERVER_DIR/.env" ] && . "$SERVER_DIR/.env"; set +a
: "${MONGO_ROOT_USERNAME:?set in .env}"
: "${MONGO_ROOT_PASSWORD:?set in .env}"

echo ">> Ensuring the X.509 \$external user exists on the replica set"
DN="$(cat "$SCRIPT_DIR/prod/app-subject-dn.txt")"
CID="$($COMPOSE ps -q mongo1)"
# docker exec (not compose exec) + host-expanded $DN avoids Compose interpolating
# the $external auth DB; \$external is escaped to reach mongosh literally.
docker exec -i "$CID" mongosh --quiet \
  --tls --tlsCAFile /etc/mongo/certs/ca.crt \
  --tlsCertificateKeyFile /etc/mongo/certs/server.pem \
  --host mongo1 -u "$MONGO_ROOT_USERNAME" -p "$MONGO_ROOT_PASSWORD" \
  --authenticationDatabase admin --eval "
    const dn = '$DN';
    const ext = db.getSiblingDB('\$external');
    if (ext.getUsers().users.some(u => u.user === dn)) {
      print('X.509 user already present: ' + dn);
    } else {
      ext.runCommand({ createUser: dn, roles: [
        { role: 'readWrite', db: 'hivora' },
        { role: 'dbAdmin',  db: 'hivora' }
      ]});
      print('Created X.509 user: ' + dn);
    }
  "
