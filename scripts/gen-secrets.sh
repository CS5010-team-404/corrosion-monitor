#!/usr/bin/env bash
# Prints fresh values for the environment variables you fill in by hand on Render or in a local .env.
set -euo pipefail
echo "ECP_SECRET_SALT=$(openssl rand -hex 8)"
echo "ECP_SECRET_KEY=$(openssl rand -base64 32)"
echo "ECP_JWT_SECRET=$(openssl rand -base64 48)   # only if you are not using the Render env group"
echo "ECP_DEMO_PASSWORD=$(openssl rand -base64 12 | tr -d '/+=')"
