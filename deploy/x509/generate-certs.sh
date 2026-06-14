#!/usr/bin/env bash
#
# Generates the X.509 PKI for a mutually-authenticated, TLS-encrypted MongoDB
# connection (MongoDB security gold standard: TLS + X.509 client auth).
#
#   ./generate-certs.sh dev     # localhost standalone (run from your IDE)
#   ./generate-certs.sh prod    # mongo1/mongo2/mongo-arbiter replica set
#
# Produces, under deploy/x509/<env>/:
#   ca.crt / ca.key            – the private certificate authority
#   server.pem                 – mongod TLS cert+key (SAN matches the hosts)
#   hivora-app.p12             – JVM keystore: the app's client cert + key
#   truststore.p12             – JVM truststore: the CA
#   app-subject-dn.txt         – the client cert DN = the Mongo $external username
#   keyfile                    – replica-set internal-auth keyfile (prod only)
#
# Idempotent-ish: refuses to overwrite an existing CA unless --force is given,
# so you don't silently invalidate certs already trusted by a running cluster.
set -euo pipefail

ENV="${1:-dev}"
FORCE="${2:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$SCRIPT_DIR/$ENV"

KS_PW="${HIVORA_MONGO_TLS_KEYSTORE_PASSWORD:-changeit}"
TS_PW="${HIVORA_MONGO_TLS_TRUSTSTORE_PASSWORD:-changeit}"
ORG="AStA Hochschule Niederrhein"

case "$ENV" in
  dev)
    SAN="subjectAltName=DNS:localhost,DNS:mongo,DNS:mongo1,IP:127.0.0.1"
    SERVER_CN="localhost" ;;
  prod)
    SAN="subjectAltName=DNS:mongo1,DNS:mongo2,DNS:mongo-arbiter,DNS:localhost,IP:127.0.0.1"
    SERVER_CN="mongo1" ;;
  *) echo "usage: $0 <dev|prod> [--force]" >&2; exit 1 ;;
esac

mkdir -p "$OUT"; cd "$OUT"

if [[ -f ca.crt && "$FORCE" != "--force" ]]; then
  echo "CA already exists in $OUT (use --force to regenerate). Skipping." >&2
  exit 0
fi

echo ">> [$ENV] Generating CA"
openssl genrsa -out ca.key 4096
openssl req -x509 -new -nodes -key ca.key -days 3650 -sha256 -out ca.crt \
  -subj "/O=$ORG/OU=Hivora/CN=Hivora $ENV Root CA"

echo ">> [$ENV] Generating mongod server certificate (SAN: $SAN)"
openssl genrsa -out server.key 4096
openssl req -new -key server.key -out server.csr -subj "/O=$ORG/OU=Hivora/CN=$SERVER_CN"
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 825 -sha256 \
  -extfile <(printf "%s\nextendedKeyUsage=serverAuth,clientAuth\nkeyUsage=digitalSignature,keyEncipherment" "$SAN") \
  -out server.crt
cat server.key server.crt > server.pem
chmod 600 server.pem server.key ca.key

# The application's client certificate. Its OU differs from the server/member
# OU so mongod treats it as a normal X.509 *user*, not a cluster member.
echo ">> [$ENV] Generating application client certificate"
openssl genrsa -out hivora-app.key 4096
openssl req -new -key hivora-app.key -out hivora-app.csr \
  -subj "/O=$ORG/OU=Hivora Application/CN=hivora-app"
openssl x509 -req -in hivora-app.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 825 -sha256 \
  -extfile <(printf "extendedKeyUsage=clientAuth\nkeyUsage=digitalSignature") \
  -out hivora-app.crt

# Combined PEM (key + cert) so mongosh inside the container can present the
# client cert when bootstrapping the $external user via the localhost exception.
cat hivora-app.key hivora-app.crt > hivora-app.pem
chmod 600 hivora-app.pem hivora-app.key

echo ">> [$ENV] Building PKCS#12 keystore + truststore for the JVM"
rm -f hivora-app.p12 truststore.p12
openssl pkcs12 -export -in hivora-app.crt -inkey hivora-app.key -name hivora-app \
  -out hivora-app.p12 -password "pass:$KS_PW"
keytool -importcert -noprompt -alias hivora-ca -file ca.crt \
  -keystore truststore.p12 -storetype PKCS12 -storepass "$TS_PW" >/dev/null

# MongoDB expects the X.509 username as the RFC2253 subject DN of the client cert.
openssl x509 -in hivora-app.crt -noout -subject -nameopt RFC2253 \
  | sed 's/^subject= *//' > app-subject-dn.txt

if [[ "$ENV" == "prod" ]]; then
  echo ">> [prod] Generating replica-set internal-auth keyfile"
  openssl rand -base64 756 > keyfile
  chmod 400 keyfile
fi

echo ""
echo "Done. Artifacts in $OUT"
echo "  Mongo X.509 username ( \$external ): $(cat app-subject-dn.txt)"
