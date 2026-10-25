#!/usr/bin/env bash
# Runs a shell command on the instance via SSM and prints its output.
#   usage: run-remote.sh "<command>"   (INSTANCE_ID and REGION must be set)
set -euo pipefail
: "${INSTANCE_ID:?}" "${REGION:?}" "${1:?command required}"
export AWS_DEFAULT_REGION=$REGION
params=$(jq -nc --arg c "$1" '{commands: [$c]}')
cmd_id=$(aws ssm send-command --instance-ids "$INSTANCE_ID" --document-name AWS-RunShellScript \
  --parameters "$params" --query Command.CommandId --output text)
while :; do
  sleep 3
  status=$(aws ssm get-command-invocation --command-id "$cmd_id" --instance-id "$INSTANCE_ID" --query Status --output text 2>/dev/null || echo Pending)
  case "$status" in Pending | InProgress | Delayed) continue ;; esac
  break
done
aws ssm get-command-invocation --command-id "$cmd_id" --instance-id "$INSTANCE_ID" \
  --query '[StandardOutputContent,StandardErrorContent]' --output text
[ "$status" = "Success" ]
