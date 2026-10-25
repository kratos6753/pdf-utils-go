#!/bin/bash
# Runs on the instance (nightly timer, or `make backup`): pg_dump to S3.
set -euo pipefail
source /etc/pdfutils.conf
export AWS_DEFAULT_REGION=$REGION
cd /opt/pdfutils
key="backups/postgres/$(date -u +%Y/%m/%d/%Y%m%dT%H%M%SZ).dump"
docker compose exec -T postgres pg_dump -U pdfutils -Fc pdfutils |
  aws s3 cp - "s3://$OPS_BUCKET/$key" --only-show-errors
echo "Backup written to s3://$OPS_BUCKET/$key"
