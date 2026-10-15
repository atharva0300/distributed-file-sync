#!/usr/bin/env bash
# ==============================================================================
# Stop FileSync AWS Environment (Zero Compute Billing while Idle)
# Usage: ./aws/stop-environment.sh [STACK_NAME] [REGION]
# ==============================================================================
set -e

STACK_NAME="${1:-filesync-prod}"
REGION="${2:-us-east-1}"

echo "==> Fetching resource IDs from CloudFormation stack '$STACK_NAME' in $REGION..."

EC2_INSTANCE_ID=$(aws cloudformation describe-stack-resources     --stack-name "$STACK_NAME"     --region "$REGION"     --logical-resource-id EC2Instance     --query "StackResourceDetail.PhysicalResourceId"     --output text 2>/dev/null || true)

RDS_INSTANCE_ID=$(aws cloudformation describe-stack-resources     --stack-name "$STACK_NAME"     --region "$REGION"     --logical-resource-id DatabaseInstance     --query "StackResourceDetail.PhysicalResourceId"     --output text 2>/dev/null || true)

if [ -n "$EC2_INSTANCE_ID" ] && [ "$EC2_INSTANCE_ID" != "None" ]; then
    echo "==> Stopping EC2 Instance ($EC2_INSTANCE_ID)..."
    aws ec2 stop-instances --instance-ids "$EC2_INSTANCE_ID" --region "$REGION" > /dev/null
    echo "    EC2 instance compute charges are paused while stopped. EBS storage charges continue, and an associated public IPv4 address may continue to incur charges."
else
    echo "    EC2 instance not found or already stopped."
fi

if [ -n "$RDS_INSTANCE_ID" ] && [ "$RDS_INSTANCE_ID" != "None" ]; then
    echo "==> Stopping RDS PostgreSQL Instance ($RDS_INSTANCE_ID)..."
    aws rds stop-db-instance --db-instance-identifier "$RDS_INSTANCE_ID" --region "$REGION" > /dev/null 2>&1 || echo "    RDS is already stopped or modifying."
    echo "    DB compute charges are paused while stopped."
    echo "    RDS storage charges continue."
    echo "    Applicable backup storage charges can continue."
    echo "    NOTE: AWS automatically restarts a stopped RDS DB instance after 7 consecutive days."
else
    echo "    RDS instance not found."
fi

echo ""
echo "=================================================================="
echo "SUCCESS: Environment stopped!"
echo "SUMMARY:"
echo "- EC2 instance compute charges are paused while stopped. EBS storage charges continue, and an associated public IPv4 address may continue to incur charges."
echo "- DB compute charges are paused while stopped."
echo "- RDS storage charges continue."
echo "- Applicable backup storage charges can continue."
echo "- AWS automatically restarts a stopped RDS DB instance after 7 consecutive days."
echo ""
echo "To restart when ready to work, run: ./aws/start-environment.sh"
echo "For the lowest ongoing cost, delete the CloudFormation stack when the project is no longer needed."
echo "=================================================================="
