#!/usr/bin/env bash
# ==============================================================================
# Distributed File Sync - EC2 Bootstrap & Zero-Downtime Installation Script
# Target OS: Amazon Linux 2023 / Ubuntu 22.04 LTS
# Microservices: 1) storage-service (:8081), 2) file-sync-service (:8080)
# ==============================================================================
set -e

echo "=== [1/6] Installing System Dependencies (Java 21, Node.js 20, Nginx, Git) ==="
if command -v dnf &> /dev/null; then
    # Amazon Linux 2023 / RHEL
    dnf update -y
    dnf install -y java-21-amazon-corretto-devel git nginx certbot python3-certbot-nginx
    # Install Node.js 20
    curl -fsSL https://rpm.nodesource.com/setup_20.x | bash -
    dnf install -y nodejs
elif command -v apt-get &> /dev/null; then
    # Ubuntu 22.04 LTS
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -y
    apt-get install -y openjdk-21-jdk git nginx curl certbot python3-certbot-nginx
    # Install Node.js 20
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
fi

# Configure 2 GB Swap File (prevents OOM during frontend & backend builds on t3.micro)
if [ ! -f /swapfile ]; then
    echo "Creating 2GB swapfile..."
    fallocate -l 2G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=2048
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
else
    echo "Swapfile already exists. Ensuring swap is active..."
    swapon /swapfile 2>/dev/null || true
fi
if ! grep -q '/swapfile' /etc/fstab; then
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

echo "=== [2/6] Setting Up Project Directory ==="
useradd -m -s /bin/bash filesync || true
mkdir -p /var/www/filesync
mkdir -p /var/log/filesync

# Set permissions
chown -R filesync:filesync /var/www/filesync /var/log/filesync

echo "=== [3/6] Setting Up Systemd Services for Two Spring Boot Services ==="

# 1. Storage Service (:8081)
cat << 'SERVICE_EOF' > /etc/systemd/system/storage-service.service
[Unit]
Description=Distributed File Sync Storage Service (CAS & S3)
After=network.target

[Service]
Type=simple
User=filesync
Group=filesync
WorkingDirectory=/var/www/filesync
EnvironmentFile=/var/www/filesync/.env
ExecStart=/usr/bin/java -Xmx200m -XX:+UseG1GC -Dserver.port=8081 -jar /var/www/filesync/storage-service/target/storage-service-1.0.0.jar
Restart=always
RestartSec=10
StandardOutput=append:/var/log/filesync/storage-service.log
StandardError=append:/var/log/filesync/storage-service-error.log

[Install]
WantedBy=multi-user.target
SERVICE_EOF

# 2. File/Sync Service (:8080)
cat << 'SERVICE_EOF' > /etc/systemd/system/filesync.service
[Unit]
Description=Distributed File Sync File/Sync Service (Metadata & Sync)
After=network.target storage-service.service
Requires=storage-service.service

[Service]
Type=simple
User=filesync
Group=filesync
WorkingDirectory=/var/www/filesync
EnvironmentFile=/var/www/filesync/.env
ExecStart=/usr/bin/java -Xmx256m -XX:+UseG1GC -jar /var/www/filesync/file-sync-service/target/file-sync-service-1.0.0.jar
Restart=always
RestartSec=10
StandardOutput=append:/var/log/filesync/file-sync-service.log
StandardError=append:/var/log/filesync/file-sync-service-error.log

[Install]
WantedBy=multi-user.target
SERVICE_EOF

systemctl daemon-reload
systemctl enable storage-service filesync

echo "=== [4/6] Configuring Nginx Reverse Proxy ==="
# Remove default nginx welcome page and configurations
rm -f /etc/nginx/conf.d/default.conf 2>/dev/null || true
rm -f /etc/nginx/sites-enabled/default 2>/dev/null || true
rm -f /etc/nginx/default.d/*.conf 2>/dev/null || true

# Configure Nginx reverse proxy (Port 80 -> File/Sync Service :8080; Storage Service :8081 is internal only)
cat << 'NGINX_EOF' > /etc/nginx/conf.d/filesync.conf
server {
    # HTTP redirect to HTTPS
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name _;
    
    # HTTPS is configured via Certbot. Run 'sudo certbot --nginx' to automatically enable HTTPS.
    # The directives below will be automatically inserted by Certbot:
    # listen 443 ssl; # managed by Certbot
    # ssl_certificate /etc/letsencrypt/live/yourdomain/fullchain.pem; # managed by Certbot
    # ssl_certificate_key /etc/letsencrypt/live/yourdomain/privkey.pem; # managed by Certbot

    client_max_body_size 110M;

    root /var/www/filesync/frontend/dist;
    index index.html;

    gzip on;
    gzip_types text/plain text/css text/xml application/javascript application/json;

    location ~* \.(?:css|js|woff2?|svg|gif|ico|png|jpg|jpeg)$ {
        expires 1y;
        add_header Cache-Control "public, max-age=31536000, immutable";
        try_files $uri =404;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }

    # Server-Sent Events stream: long-lived GET, own read timeout, buffering off
    # so nginx streams events instead of holding them.
    location /api/sync/events {
        proxy_pass http://127.0.0.1:8080/api/sync/events;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_buffering off;
        proxy_read_timeout 86400s;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080/api/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 60s;
        proxy_send_timeout 120s;
        proxy_read_timeout 120s;
    }

    location /swagger-ui/ {
        proxy_pass http://127.0.0.1:8080/swagger-ui/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    location /api-docs {
        proxy_pass http://127.0.0.1:8080/api-docs;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
NGINX_EOF

nginx -t
systemctl restart nginx
systemctl enable nginx

echo "=== [5/6] Bootstrap Completed! ==="
echo "To deploy code:"
echo "  1. Copy project files into /var/www/filesync"
echo "  2. Populate /var/www/filesync/.env with RDS credentials, S3 bucket name, and STORAGE_SERVICE_URL=http://localhost:8081"
echo "  3. Build frontend: cd /var/www/filesync/frontend && npm install && npm run build"
echo "  4. Build backend:  cd /var/www/filesync && ./mvnw clean package -DskipTests"
echo "  5. Start services: systemctl restart storage-service filesync && systemctl enable storage-service filesync"
echo "  6. Enable HTTPS:   Run 'sudo certbot --nginx' and follow the prompts"
