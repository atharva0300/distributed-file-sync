#!/usr/bin/env bash
# ==============================================================================
# Helper Script: Deploy Application to EC2 Instance (Two-Service Architecture)
# Usage: ./aws/deploy.sh <EC2_PUBLIC_IP> <PATH_TO_PEM_KEY>
# ==============================================================================
set -e

EC2_IP="$1"
KEY_PATH="$2"

if [ -z "$EC2_IP" ] || [ -z "$KEY_PATH" ]; then
    echo "Usage: $0 <EC2_PUBLIC_IP> <SSH_PRIVATE_KEY_PATH>"
    echo "Example: $0 54.210.12.34 ~/.ssh/filesync-key.pem"
    exit 1
fi

SSH_USER="ec2-user"

echo "==> Testing SSH connection to $SSH_USER@$EC2_IP..."
ssh -o StrictHostKeyChecking=no -i "$KEY_PATH" "$SSH_USER@$EC2_IP" "echo 'SSH Connection verified!'"

echo "==> Synchronizing project files to /var/www/filesync..."
rsync -avz --exclude '.git' --exclude 'target' --exclude '*/target' --exclude 'node_modules' --exclude 'frontend/node_modules' --exclude '/storage' --exclude 'explanations' --exclude '.env' --exclude '*/.env' -e "ssh -i $KEY_PATH" ./ "$SSH_USER@$EC2_IP:/tmp/filesync-source"

echo "==> Building and deploying both services on EC2..."
ssh -t -i "$KEY_PATH" "$SSH_USER@$EC2_IP" << 'REMOTE_COMMANDS'
sudo rsync -av --delete --exclude '.env' --exclude '*/.env' /tmp/filesync-source/ /var/www/filesync/
cd /var/www/filesync/frontend
npm install
npm run build
cd /var/www/filesync
./mvnw clean package -DskipTests
sudo systemctl enable storage-service filesync
sudo systemctl restart storage-service filesync
sudo systemctl restart nginx
echo "==> Deployment Complete! Check status with: sudo systemctl status storage-service filesync"
echo "==> Note: If you want to configure HTTPS, SSH into the instance and run: sudo certbot --nginx"
REMOTE_COMMANDS

echo "==> FileSync is live at: http://$EC2_IP"
