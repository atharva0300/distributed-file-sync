#!/bin/bash
# ==============================================================================
# Distributed File Sync - Local Development Orchestrator (Two-Service Architecture)
# Starts:
#   1. PostgreSQL 16 (Port 5432 via Docker Compose)
#   2. Storage Service (Port 8081 via Spring Boot)
#   3. File/Sync Service (Port 8080 via Spring Boot)
#   4. React Frontend (Port 5173 via Vite)
# ==============================================================================
set -e

# ── Colors & Formatting ───────────────────────────────────────────────────────
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BOLD='\033[1m'
NC='\033[0m' # No Color

echo -e "${BOLD}${BLUE}================================================================${NC}"
echo -e "${BOLD}${BLUE}  Distributed File Sync — Local Development Orchestrator        ${NC}"
echo -e "${BOLD}${BLUE}  (Minimal Two-Service Architecture: Port 8080 & Port 8081)    ${NC}"
echo -e "${BOLD}${BLUE}================================================================${NC}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

# ── 1. Pre-Flight Checks ──────────────────────────────────────────────────────
echo -e "\n${BOLD}[1/5] Checking prerequisites...${NC}"

# Check Java
if ! command -v java >/dev/null 2>&1; then
  echo -e "${RED}Error: 'java' is not installed or not in PATH. Please install Java 21.${NC}"
  exit 1
fi
JAVA_VER=$(java -version 2>&1 | head -n 1 | awk -F '"' '{print $2}' | cut -d'.' -f1)
echo -e "  ✓ Java detected (version: ${GREEN}$JAVA_VER${NC})"

# Check Node / npm
if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
  echo -e "${RED}Error: 'node' or 'npm' is not installed. Please install Node.js 18+.${NC}"
  exit 1
fi
NODE_VER=$(node -v)
echo -e "  ✓ Node.js detected (${GREEN}$NODE_VER${NC})"

# Check Maven Wrapper
if [ ! -f "./mvnw" ]; then
  echo -e "${RED}Error: Maven wrapper './mvnw' not found in $ROOT_DIR.${NC}"
  exit 1
fi
chmod +x ./mvnw
echo -e "  ✓ Maven wrapper ready"

# Initialize .env if missing
if [ ! -f .env ]; then
  if [ -f .env.example ]; then
    echo -e "  ${YELLOW}! .env file missing. Generating from .env.example...${NC}"
    cp .env.example .env
  else
    echo -e "  ${YELLOW}! Creating default .env file...${NC}"
    cat << 'EOF' > .env
POSTGRES_DB=filesync
POSTGRES_USER=filesync
POSTGRES_PASSWORD=filesync_secret
DATABASE_URL=jdbc:postgresql://localhost:5432/filesync
DATABASE_USERNAME=filesync
DATABASE_PASSWORD=filesync_secret
STORAGE_SERVICE_URL=http://localhost:8081
JWT_SECRET=development_secret_key_that_is_at_least_256_bits_long_for_testing
JWT_EXPIRATION_MS=86400000
STORAGE_TYPE=local
STORAGE_PATH=./storage
CHUNK_SIZE=4194304
INTERNAL_API_KEY=dev-internal-key
EOF
  fi
fi

# Load .env
set -a
# shellcheck disable=SC1091
source .env
set +a
echo -e "  ✓ Environment loaded from .env"

# Create logs & storage directory
mkdir -p logs storage

# ── 2. Port Sanitation ────────────────────────────────────────────────────────
echo -e "\n${BOLD}[2/5] Sanitizing ports (5432, 8080, 8081, 5173)...${NC}"
for port in 5432 8080 8081 5173; do
  PIDS=$(lsof -t -i:"$port" 2>/dev/null || true)
  if [ -n "$PIDS" ]; then
    # Don't kill Docker proxy if it's the target postgres container
    if [ "$port" -eq 5432 ] && docker ps 2>/dev/null | grep -q "filesync-postgres"; then
      echo -e "  ✓ Port 5432 is already held by filesync-postgres container"
      continue
    fi
    echo -e "  ${YELLOW}Freeing port $port held by PID(s): $PIDS...${NC}"
    kill -9 $PIDS 2>/dev/null || true
  else
    echo -e "  ✓ Port $port is free"
  fi
done

# ── 3. Start PostgreSQL + Redis ───────────────────────────────────────────────
echo -e "\n${BOLD}[3/5] Initializing PostgreSQL (Port 5432) and Redis (Port 6379)...${NC}"
if docker info >/dev/null 2>&1; then
  if docker compose version >/dev/null 2>&1; then
    docker compose up -d postgres redis
  else
    docker-compose up -d postgres redis
  fi

  echo -n "  Waiting for PostgreSQL database engine..."
  READY=0
  for i in $(seq 1 30); do
    if docker compose exec -T postgres pg_isready -U "${POSTGRES_USER:-filesync}" -d "${POSTGRES_DB:-filesync}" >/dev/null 2>&1; then
      READY=1
      break
    fi
    echo -n "."
    sleep 1
  done
  echo ""

  if [ $READY -eq 1 ]; then
    echo -e "  ${GREEN}✓ PostgreSQL is healthy and accepting connections!${NC}"

    # Handle manual reset flag or detect legacy monolithic schema
    RESET_TRIGGERED=0
    if [ "$1" == "--reset-db" ] || [ "$1" == "--clean" ]; then
      RESET_TRIGGERED=1
      echo -e "  ${YELLOW}! Resetting database schema (--reset-db requested)...${NC}"
    elif docker compose exec -T postgres psql -U "${POSTGRES_USER:-filesync}" -d "${POSTGRES_DB:-filesync}" -tAc "SELECT 1 FROM information_schema.tables WHERE table_name='flyway_schema_history';" 2>/dev/null | grep -q 1; then
      if docker compose exec -T postgres psql -U "${POSTGRES_USER:-filesync}" -d "${POSTGRES_DB:-filesync}" -tAc "SELECT script FROM flyway_schema_history WHERE script='V1__init_schema.sql';" 2>/dev/null | grep -q "V1__init_schema.sql"; then
        RESET_TRIGGERED=1
        echo -e "  ${YELLOW}! Detected legacy schema from monolithic prototype. Re-initializing schema for two-service architecture...${NC}"
      fi
    fi

    if [ $RESET_TRIGGERED -eq 1 ]; then
      docker compose exec -T postgres psql -U "${POSTGRES_USER:-filesync}" -d "${POSTGRES_DB:-filesync}" -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;" >/dev/null 2>&1 || true
      echo -e "  ${GREEN}✓ Clean schema initialized for Flyway migration.${NC}"
    fi
  else
    echo -e "  ${RED}Warning: PostgreSQL took too long to start. Continuing...${NC}"
  fi
else
  echo -e "  ${YELLOW}Warning: Docker daemon not running. Assuming PostgreSQL is running locally on port 5432.${NC}"
fi

# ── Setup Process Cleanup Trap ────────────────────────────────────────────────
STORAGE_PID=""
BACKEND_PID=""
FRONTEND_PID=""

cleanup() {
  echo -e "\n\n${YELLOW}=== Shutting down all services... ===${NC}"
  if [ -n "$FRONTEND_PID" ]; then
    kill "$FRONTEND_PID" 2>/dev/null || true
    echo "  ✓ Stopped Frontend (PID $FRONTEND_PID)"
  fi
  if [ -n "$BACKEND_PID" ]; then
    kill "$BACKEND_PID" 2>/dev/null || true
    echo "  ✓ Stopped File/Sync Service (PID $BACKEND_PID)"
  fi
  if [ -n "$STORAGE_PID" ]; then
    kill "$STORAGE_PID" 2>/dev/null || true
    echo "  ✓ Stopped Storage Service (PID $STORAGE_PID)"
  fi
  echo -e "${GREEN}All local services stopped cleanly.${NC}"
  exit 0
}

trap cleanup INT TERM

# ── 4. Start Backend Microservices ────────────────────────────────────────────
echo -e "\n${BOLD}[4/5] Launching Backend Microservices...${NC}"

# A. Storage Service (:8081)
echo -e "  → Starting ${BOLD}Storage Service${NC} (Port 8081 - Content-Addressable Storage)..."
./mvnw -pl storage-service spring-boot:run > logs/storage-service.log 2>&1 &
STORAGE_PID=$!

echo -n "    Waiting for Storage Service health probe"
STORAGE_READY=0
for i in $(seq 1 45); do
  if curl -s http://localhost:8081/internal/health 2>/dev/null | grep -q '"status":"UP"'; then
    STORAGE_READY=1
    break
  fi
  echo -n "."
  sleep 1
done
echo ""

if [ $STORAGE_READY -eq 1 ]; then
  echo -e "    ${GREEN}✓ Storage Service is healthy on http://localhost:8081${NC}"
else
  echo -e "    ${RED}✗ Storage Service failed to become ready! Last 20 lines of log:${NC}"
  tail -n 20 logs/storage-service.log
  cleanup
  exit 1
fi

# B. File/Sync Service (:8080)
echo -e "  → Starting ${BOLD}File/Sync Service${NC} (Port 8080 - Metadata, Auth & Sync Engine)..."
./mvnw -pl file-sync-service spring-boot:run > logs/file-sync-service.log 2>&1 &
BACKEND_PID=$!

echo -n "    Waiting for File/Sync Service API docs"
BACKEND_READY=0
for i in $(seq 1 60); do
  if curl -s http://localhost:8080/api-docs >/dev/null 2>&1; then
    BACKEND_READY=1
    break
  fi
  echo -n "."
  sleep 1
done
echo ""

if [ $BACKEND_READY -eq 1 ]; then
  echo -e "    ${GREEN}✓ File/Sync Service is ready on http://localhost:8080${NC}"
else
  echo -e "    ${RED}✗ File/Sync Service failed to become ready! Last 20 lines of log:${NC}"
  tail -n 20 logs/file-sync-service.log
  cleanup
  exit 1
fi

# ── 5. Start React Frontend ───────────────────────────────────────────────────
echo -e "\n${BOLD}[5/5] Launching React Frontend (Port 5173)...${NC}"
cd frontend
if [ ! -d "node_modules" ]; then
  echo "  Installing frontend dependencies (npm install)..."
  npm install --silent
fi
npm run dev > ../logs/frontend.log 2>&1 &
FRONTEND_PID=$!
cd ..

echo -n "  Waiting for Vite development server"
FRONTEND_READY=0
for i in $(seq 1 30); do
  if curl -s http://localhost:5173 >/dev/null 2>&1; then
    FRONTEND_READY=1
    break
  fi
  echo -n "."
  sleep 1
done
echo ""

if [ $FRONTEND_READY -eq 1 ]; then
  echo -e "  ${GREEN}✓ React Frontend is ready on http://localhost:5173${NC}"
else
  echo -e "  ${YELLOW}! Vite server is starting up in the background.${NC}"
fi

# ── Success Banner ────────────────────────────────────────────────────────────
echo -e "\n${BOLD}${GREEN}================================================================${NC}"
echo -e "${BOLD}${GREEN}  🎉 Distributed File Sync Platform is completely ONLINE!       ${NC}"
echo -e "${BOLD}${GREEN}================================================================${NC}"
echo -e "  • ${BOLD}Web Dashboard${NC}        : ${BLUE}http://localhost:5173${NC}"
echo -e "  • ${BOLD}File/Sync Service API${NC}: ${BLUE}http://localhost:8080${NC}"
echo -e "  • ${BOLD}Swagger Documentation${NC}: ${BLUE}http://localhost:8080/swagger-ui.html${NC}"
echo -e "  • ${BOLD}Storage Service (CAS)${NC}: ${BLUE}http://localhost:8081/internal/health${NC}"
echo -e "  • ${BOLD}PostgreSQL Database${NC}  : ${BLUE}localhost:5432 (db: ${POSTGRES_DB:-filesync})${NC}"
echo -e "----------------------------------------------------------------"
echo -e "  ${BOLD}Live Log Streaming Commands:${NC}"
echo -e "    tail -f logs/file-sync-service.log"
echo -e "    tail -f logs/storage-service.log"
echo -e "    tail -f logs/frontend.log"
echo -e "================================================================"
echo -e "${BOLD}Press Ctrl+C to stop all services simultaneously.${NC}\n"

# Keep script running and waiting for interrupt
wait
