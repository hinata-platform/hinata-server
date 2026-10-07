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
echo "HINATA_JWT_SECRET=$(openssl rand -base64 64 | tr -d '\n=' | cut -c1-86)"
echo "MONGO_ROOT_PASSWORD=$(openssl rand -hex 24)"
echo "HINATA_S3_SECRET_KEY=$(openssl rand -hex 32)"
echo "SEAWEEDFS_ADMIN_SECRET_KEY=$(openssl rand -hex 32)"
echo "SEAWEEDFS_SSE_KEK=$(openssl rand -hex 32)   # keep a copy apart from the server"
echo "SEAWEEDFS_JWT_VOLUME_WRITE=$(openssl rand -hex 32)"
echo "SEAWEEDFS_JWT_VOLUME_READ=$(openssl rand -hex 32)"
echo "SEAWEEDFS_JWT_FILER_WRITE=$(openssl rand -hex 32)"
echo "SEAWEEDFS_JWT_FILER_READ=$(openssl rand -hex 32)"
