#!/usr/bin/env sh
# Generates the MongoDB replica set keyfile and prints suggested secrets.
set -eu

cd "$(dirname "$0")"

if [ ! -f mongo-keyfile ]; then
  openssl rand -base64 756 > mongo-keyfile
  chmod 400 mongo-keyfile
  echo "Created deploy/mongo-keyfile"
else
  echo "deploy/mongo-keyfile already exists - keeping it"
fi

echo
echo "Suggested secrets for your .env:"
echo "HIVORA_JWT_SECRET=$(openssl rand -base64 64 | tr -d '\n=' | cut -c1-86)"
echo "MONGO_ROOT_PASSWORD=$(openssl rand -hex 24)"
echo "MINIO_ROOT_PASSWORD=$(openssl rand -hex 24)"
