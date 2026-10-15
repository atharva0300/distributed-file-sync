# AWS Setup & Deployment Guide: Cost-Conscious Architecture

This guide explains how to deploy the **Distributed File Sync** platform to AWS using **EC2, S3, RDS (PostgreSQL), and VPC** with an ultra-minimal footprint tailored for personal demo and technical interview usage (~15–20 hours/month).

---

## 1. AWS Free Account Plan & Cost Guardrails

> [!IMPORTANT]
> **AWS Free Account Plan & Credit Model**:
> - **$100 AWS Credits**: New AWS accounts receive $100 in AWS credits upon registration.
> - **Up to 6-Month Free Plan**: The Free account plan can be used for up to 6 months or until credits are exhausted. Charges do not apply while remaining on the Free account plan.
> - **Upgrade to Paid Plan**: Any usage that is not covered by the Free plan or credits requires upgrading to the Paid plan. Compute (EC2/RDS) and public IPv4 are not guaranteed to be $0.
> - **Public IPv4 Addresses**: AWS charges $0.005/hour for in-use public IPv4 addresses unless covered by account plan benefits.
> - **Provisioned Storage Continues While Stopped**: The 20 GB gp3 EBS root volume, 20 GB gp3 RDS storage, and applicable backup storage continue to incur monthly charges while resources remain provisioned, even when instances are stopped.
> - **RDS Automatic 7-Day Restart**: AWS automatically restarts stopped RDS DB instances after 7 consecutive days. Stopping RDS pauses compute charges while stopped, but does not eliminate storage charges.
> - **Safest Workflow**: For this temporary interview/demo project, deploy the stack on-demand when actively presenting or testing (~15–20 hours/month), and **delete the CloudFormation stack** after use as the safest way to halt all ongoing infrastructure charges.

> [!CAUTION]
> **Avoid the Top 3 AWS Bill Traps**:
> 1. **Do NOT create a NAT Gateway**: A NAT Gateway costs **$32.40/month** just to exist. Our design uses **$0.00 NAT Gateways** by using a Public Subnet with strict Security Groups and a **free S3 VPC Gateway Endpoint**.
> 2. **Do NOT enable Multi-AZ on RDS**: Multi-AZ doubles instance hours and storage footprint, draining credits rapidly.
> 3. **Do NOT allocate unused Elastic IPs**: An allocated Elastic IP that is not attached to a running instance costs $0.005/hr. Use the EC2 auto-assigned public IP instead.

---

## 2. Pre-Configured Billing Safety & Monitoring

The AWS account already has comprehensive cost guardrails and alerting configured. **Do NOT create a second or duplicate budget.**

### Active Billing Protections:
- **AWS Free Tier Usage Alerts**: Enabled (automatically notifies when service usage approaches tier thresholds).
- **CloudWatch Billing Alerts**: Enabled.
- **Active Budget Name**: `AWS-Personal-Safety-Budget`
  - **Monthly Target Budget**: `$5.00`
  - **Actual Spend Alert Threshold 1**: `$1.00` (triggers email notification)
  - **Actual Spend Alert Threshold 2**: `$2.50` (triggers email notification)
  - **Forecasted Spend Alert**: `$4.00` (triggers when projected month-end spend exceeds 80% of budget)
- **Cost Explorer**: Enabled (historical data preparing).
- **Cost Anomaly Detection**: Active (flags anomalous spending spikes across services).

> [!CAUTION]
> **CRITICAL BILLING REALITY**:
> **A budget is an alerting mechanism, NOT a guaranteed hard spending cap.**
> AWS Budgets will email you when spending thresholds are breached, but **AWS will NOT automatically shut down your resources**. You must actively stop instances or delete the CloudFormation stack to halt ongoing costs.

---

## 3. Option A: 1-Click Deployment via CloudFormation (Recommended)

The easiest and safest way to provision the entire architecture without manual errors is using the included CloudFormation template [`aws/cloudformation.yml`](file:///workspace/distributed-file-sync/aws/cloudformation.yml).

### Step 1: Create an EC2 Key Pair (for SSH)
1. Go to **EC2 Console** -> **Key Pairs** (under Network & Security).
2. Click **Create key pair**.
3. Name it `filesync-key`, select **RSA** and `.pem` (for Mac/Linux/WSL) or `.ppk` (for PuTTY).
4. Download and save the key to `~/.ssh/filesync-key.pem` and run `chmod 400 ~/.ssh/filesync-key.pem`.

### Step 2: Deploy CloudFormation Stack
1. Open the [AWS CloudFormation Console](https://console.aws.amazon.com/cloudformation/).
2. Select Region: **us-east-1** (N. Virginia).
3. Click **Create stack** -> **With new resources (standard)**.
4. Select **Upload a template file** -> Choose [`aws/cloudformation.yml`](file:///workspace/distributed-file-sync/aws/cloudformation.yml).
5. Click **Next**.
6. Fill in the parameters:
   - **Stack name**: `filesync-prod`
   - **KeyName**: Select `filesync-key`
   - **AdminIP**: Enter your workstation's public IPv4 address in `/32` CIDR format (e.g. `203.0.113.25/32`, or check via `curl -s ifconfig.me`). You **MUST** replace the dummy default (`192.0.2.1/32`) so you can SSH into the instance. Never use `0.0.0.0/0`.
   - **DBUsername**: `filesync_admin`
   - **DBPassword**: Enter a secure password (e.g. `FileSyncSecret2026!`)
   - **EC2InstanceType**: `t3.micro` (Standard x86_64 instance)
   - **RDSInstanceType**: `db.t3.micro` (Single-AZ minimal database)
7. Click **Next** -> **Next**.
8. At the bottom, check **"I acknowledge that AWS CloudFormation might create IAM resources"**.
9. Click **Submit**.

CloudFormation will automatically create:
- The VPC, Subnets, and S3 Gateway Endpoint
- The S3 Bucket with encryption and 1-day multipart lifecycle rule
- The RDS PostgreSQL instance and Security Groups
- The IAM Instance Profile and EC2 instance

### Step 3: View Outputs
Once the stack status is `CREATE_COMPLETE` (~8-10 minutes for RDS initialization):
1. Click the **Outputs** tab.
2. Note:
   - `EC2PublicIP`: The public IP of your server.
   - `AppURL`: `http://<EC2PublicIP>`
   - `RDSEndpoint`: The PostgreSQL host address.
   - `S3BucketName`: The generated S3 bucket name.

---

## 4. Option B: Manual AWS Console Setup (If You Prefer Clicking)

If you prefer setting up resources individually:

### 1. Amazon S3 Bucket
- Service: **S3** -> **Create bucket**
- Bucket name: `filesync-chunks-<your-aws-account-id>`
- Region: `us-east-1`
- Block Public Access: **Keep all checked** (Private bucket)
- Bucket Encryption: **SSE-S3** (Default AES256)
- Lifecycle rules: Create rule named `abort-incomplete-multipart` -> Check **Delete expired object delete markers or incomplete multipart uploads** -> 1 day.

### 2. RDS PostgreSQL Database
- Service: **RDS** -> **Create database**
- Templates: **Dev/Test** -> Choose Single-AZ and instance class `db.t3.micro` (or `db.t4g.micro`)
- DB instance identifier: `filesync-postgres`
- Master username: `filesync_admin`
- Master password: Enter your secure password
- Storage: 20 GB gp3 SSD (Disable storage autoscaling to avoid unexpected size jumps)
- Connectivity:
  - Public access: **No**
  - VPC security group: Create new `filesync-rds-sg`
- Click **Create database**.

### 3. IAM Role for EC2
- Service: **IAM** -> **Roles** -> **Create role**
- Trusted entity type: **AWS service** -> Use case: **EC2**
- Permissions policy: Click **Create policy** -> JSON:
  ```json
  {
    "Version": "2012-10-17",
    "Statement": [
      {
        "Effect": "Allow",
        "Action": [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:ListBucket"
        ],
        "Resource": [
          "arn:aws:s3:::filesync-chunks-<account-id>",
          "arn:aws:s3:::filesync-chunks-<account-id>/*"
        ]
      }
    ]
  }
  ```
- Name the policy `FileSyncS3Policy` and role `FileSyncEC2Role`.

### 4. EC2 Instance
- Service: **EC2** -> **Launch instances**
- Name: `filesync-server`
- AMI: **Amazon Linux 2023** (x86_64)
- Instance type: **t3.micro** (x86_64, standard minimal instance)
- Key pair: `filesync-key`
- Network settings:
  - Auto-assign public IP: **Enable**
  - Security group: Allow SSH (port 22) from your IP, Allow HTTP (port 80) from anywhere, Allow HTTPS (port 443) from anywhere.
- Advanced details:
  - IAM instance profile: Select `FileSyncEC2Role`
- Click **Launch instance**.

### 5. Allow EC2 to talk to RDS
- Go to RDS -> Click your database -> Connectivity & security -> Click the VPC security group (`filesync-rds-sg`).
- Inbound rules -> **Edit inbound rules** -> Add rule:
  - Type: **PostgreSQL** (Port 5432)
  - Source: Select the EC2 Security Group ID (`sg-xxxxxxxx`).

---

## 5. Deploying the Code to EC2

Once the EC2 instance is running:

### Step 1: Run the Bootstrap Script
SSH into the EC2 instance:
```bash
ssh -i ~/.ssh/filesync-key.pem ec2-user@<EC2_PUBLIC_IP>
```

Run the bootstrap setup (installs Java 21, Node.js 20, Nginx, configures 2GB swap, sets up directories and systemd unit files):
```bash
curl -fsSL https://raw.githubusercontent.com/.../aws/ec2-userdata.sh | sudo bash
# Or copy aws/ec2-userdata.sh to the instance and run:
sudo bash aws/ec2-userdata.sh
```

### Step 2: Create & Populate `/var/www/filesync/.env` on EC2
On the EC2 instance, create and edit `/var/www/filesync/.env`:
```bash
sudo nano /var/www/filesync/.env
```

Populate it with your actual RDS endpoint (from CloudFormation outputs), database credentials, and S3 bucket name:
```env
AWS_REGION=us-east-1
S3_BUCKET_NAME=filesync-chunks-YOUR_ACCOUNT_ID
STORAGE_TYPE=s3
STORAGE_PORT=8081
STORAGE_SERVICE_URL=http://localhost:8081

DATABASE_URL=jdbc:postgresql://<YOUR_RDS_ENDPOINT>:5432/filesync
DATABASE_USERNAME=filesync_admin
DATABASE_PASSWORD=<YOUR_RDS_PASSWORD>

JWT_SECRET=your_super_strong_random_jwt_secret_at_least_256_bits
JWT_EXPIRATION_MS=86400000
SERVER_PORT=8080
```

Ensure correct file permissions:
```bash
sudo chmod 600 /var/www/filesync/.env
```

### Step 3: Deploy Project Code (From Local Workstation)
From your local workstation terminal, run the automated deploy script:
```bash
./aws/deploy.sh <EC2_PUBLIC_IP> ~/.ssh/filesync-key.pem
```

This script will:
1. Sync project source code to EC2 (safely excluding local `.env` and build artifacts).
2. Install frontend dependencies and build the static assets (`npm run build`).
3. Build the backend microservices with Maven (`./mvnw clean package -DskipTests`).
4. Enable and start both `storage-service` and `filesync` systemd services.
5. Reload Nginx.

### Step 4: Check Service Status and Logs
SSH back into the EC2 instance to verify both services:
```bash
# Verify both services are active (running)
sudo systemctl status storage-service filesync

# Follow live Storage Service logs (:8081)
sudo journalctl -u storage-service -f

# Follow live File/Sync Service logs (:8080)
sudo journalctl -u filesync -f
```

---

## 6. Verification Checklist

1. Open your browser to `http://<EC2_PUBLIC_IP>`.
2. Register a new user account.
3. Upload a file (e.g., `sample.pdf` or `photo.jpg`).
4. Check that chunks are deduplicated and written to S3:
   - Go to **AWS S3 Console** -> `filesync-chunks-...` bucket.
   - Observe objects stored under `chunks/xx/<sha256-hash>`.
5. Check RDS database records:
   - Tables `users`, `files`, `chunks`, `file_versions`, and `file_version_chunks` are populated.
6. Verify real-time push (Server-Sent Events):
   - Connect a second browser window with the same account and see synchronized changes live.

---

## 7. Managing Costs: Pausing Compute vs. Complete Teardown

### Option A: Temporarily Pausing Compute (Day-to-Day Demo Use)
When you finish a demo session for the day and want to pause active compute charges:
```bash
./aws/stop-environment.sh filesync-prod us-east-1
```
When ready to resume testing:
```bash
./aws/start-environment.sh filesync-prod us-east-1
```

> [!WARNING]
> **Idle Storage & Automatic Restart Notice**:
> - Stopping EC2 and RDS pauses per-second compute charges, but **provisioned storage (20 GB gp3 EBS root volume, 20 GB gp3 RDS storage) and applicable backup storage continue to incur monthly charges while provisioned**.
> - Public IPv4 addresses may also continue to incur hourly charges ($0.005/hour) unless covered by account plan benefits.
> - AWS **automatically restarts stopped RDS database instances after 7 consecutive days**.

### Option B: Complete Teardown (Preferred Final Teardown Method)
For the lowest ongoing cost, **deleting the CloudFormation stack after use is the preferred teardown method** for this temporary interview project:
1. **Empty the S3 Bucket**: Go to **S3** -> Select `filesync-chunks-...` bucket -> Click **Empty** -> Type `permanently delete` (or run `aws s3 rm s3://<bucket-name> --recursive`).
2. **Delete CloudFormation Stack**: Go to **CloudFormation** -> Select `filesync-prod` -> Click **Delete** (or run `aws cloudformation delete-stack --stack-name filesync-prod`). This cleanly terminates the EC2 instance, EBS root volume, RDS database, VPC, and all associated security groups in minutes.
