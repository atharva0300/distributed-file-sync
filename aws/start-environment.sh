#!/usr/bin/env bash
# ==============================================================================
# Start FileSync AWS Environment
# Usage: ./aws/start-environment.sh [STACK_NAME] [REGION]
# ==============================================================================
set -e

STACK_NAME="${1:-filesync-prod}"
REGION="${2:-us-east-1}"

echo "==> Fetching resource IDs from CloudFormation stack '$STACK_NAME' in $REGION..."

EC2_INSTANCE_ID=$(aws cloudformation describe-stack-resources     --stack-name "$STACK_NAME"     --region "$REGION"     --logical-resource-id EC2Instance     --query "StackResourceDetail.PhysicalResourceId"     --output text 2>/dev/null || true)

RDS_INSTANCE_ID=$(aws cloudformation describe-stack-resources     --stack-name "$STACK_NAME"     --region "$REGION"     --logical-resource-id DatabaseInstance     --query "StackResourceDetail.PhysicalResourceId"     --output text 2>/dev/null || true)

if [ -n "$RDS_INSTANCE_ID" ] && [ "$RDS_INSTANCE_ID" != "None" ]; then
    echo "==> Starting RDS PostgreSQL Instance ($RDS_INSTANCE_ID)..."
    aws rds start-db-instance --db-instance-identifier "$RDS_INSTANCE_ID" --region "$REGION" > /dev/null 2>&1 || echo "    RDS is already running or starting."
fi

if [ -n "$EC2_INSTANCE_ID" ] && [ "$EC2_INSTANCE_ID" != "None" ]; then
    echo "==> Starting EC2 Instance ($EC2_INSTANCE_ID)..."
    aws ec2 start-instances --instance-ids "$EC2_INSTANCE_ID" --region "$REGION" > /dev/null
    echo "==> Waiting for EC2 public IP..."
    aws ec2 wait instance-running --instance-ids "$EC2_INSTANCE_ID" --region "$REGION"
    NEW_IP=$(aws ec2 describe-instances --instance-ids "$EC2_INSTANCE_ID" --region "$REGION" --query "Reservations[0].Instances[0].PublicIpAddress" --output text)
    echo ""
    echo "=================================================================="
    echo "SUCCESS: Environment started!"
    echo "EC2 Public IP: $NEW_IP"
    echo "Web Application URL: http://$NEW_IP"
    echo "=================================================================="
fi
