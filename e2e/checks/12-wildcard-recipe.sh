#!/usr/bin/env sh
set -eu

sed -e s/LETSENCRYPT_EMAIL/e2e@liftgate.test/ -e s/DNS_SERVER/192.0.2.53:53/ -e s/TSIG_KEY_NAME/liftgate/ -e s/DEPLOY_DOMAIN/liftgate.app/ \
  infra/cert-manager/wildcard.yaml | kubectl -n liftgate-system apply --dry-run=server -f -
