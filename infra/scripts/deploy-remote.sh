#!/usr/bin/env bash
# Points the instance at image TAG and runs its deploy script over SSM, then
# prints the output. Used by Terraform, `make deploy` and CI.
#   INSTANCE_ID REGION SSM_PREFIX TAG must be set.
set -euo pipefail
: "${INSTANCE_ID:?}" "${REGION:?}" "${SSM_PREFIX:?}" "${TAG:?}"
export AWS_DEFAULT_REGION=$REGION

aws ssm put-parameter --name "$SSM_PREFIX/image_tag" --value "$TAG" --type String --overwrite >/dev/null

echo "Waiting for $INSTANCE_ID to register with SSM…"
for i in $(seq 1 90); do
  status=$(aws ssm describe-instance-information --filters "Key=InstanceIds,Values=$INSTANCE_ID" \
    --query 'InstanceInformationList[0].PingStatus' --output text 2>/dev/null || true)
  [ "$status" = "Online" ] && break
  [ "$i" = 90 ] && { echo "Instance never came online in SSM" >&2; exit 1; }
  sleep 10
done

# On first boot, let cloud-init finish installing Docker before deploying.
cmd_id=$(aws ssm send-command --instance-ids "$INSTANCE_ID" --document-name AWS-RunShellScript \
  --comment "deploy $TAG" --timeout-seconds 1200 \
  --parameters '{"commands":["cloud-init status --wait >/dev/null || true","/usr/local/bin/pdfutils-deploy"],"executionTimeout":["1200"]}' \
  --query Command.CommandId --output text)
echo "Deploying $TAG (SSM command $cmd_id)…"

while :; do
  sleep 10
  status=$(aws ssm get-command-invocation --command-id "$cmd_id" --instance-id "$INSTANCE_ID" \
    --query Status --output text 2>/dev/null || echo Pending)
  case "$status" in Pending | InProgress | Delayed) continue ;; esac
  break
done

aws ssm get-command-invocation --command-id "$cmd_id" --instance-id "$INSTANCE_ID" \
  --query '[StandardOutputContent,StandardErrorContent]' --output text | tail -n 40
if [ "$status" != "Success" ]; then
  echo "Deploy failed: $status" >&2
  exit 1
fi
echo "Deploy succeeded."
