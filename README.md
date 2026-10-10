# Distributed File Synchronization Service

[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.2-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue.svg)](https://www.postgresql.org/)
[![Amazon S3](https://img.shields.io/badge/AWS-S3%20%7C%20EC2%20%7C%20RDS-FF9900.svg)](https://aws.amazon.com/)
[![React 18](https://img.shields.io/badge/React-18-61DAFB.svg)](https://react.dev/)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.5-blue.svg)](https://www.typescriptlang.org/)

A Dropbox-like file synchronization system, split into two services: `file-sync-service`
owns metadata, versions, delta sync and conflict detection; `storage-service` owns
content-addressable chunk storage. Files are split into variable-size chunks using
content-defined chunking (a rolling hash, not fixed byte offsets), identified by their
SHA-256 hash, which is what makes chunk-level deduplication and delta sync work.

## Table of Contents
1. [Architecture](#1-architecture)
2. [Why Two Services](#2-why-two-services)
3. [Content-Defined Chunking](#3-content-defined-chunking)
4. [Upload Flow](#4-upload-flow)
5. [Delta Sync](#5-delta-sync)
6. [Conflict Handling](#6-conflict-handling)
7. [Push Notifications (SSE)](#7-push-notifications-sse)
8. [Database Model](#8-database-model)
9. [AWS Deployment](#9-aws-deployment)
10. [Key Design Decisions](#10-key-design-decisions)
11. [Local Development](#11-local-development)
12. [Repository Structure](#12-repository-structure)

---

## 1. Architecture

```
                         Internet
                            │
                            ▼
                          Nginx (Port 80 / 443)
                            │
                            │ reverse proxy (/api/*, incl. /api/sync/events for SSE)
                            ▼
                  ┌─────────────────────┐
                  │  file-sync-service  │
                  │  Spring Boot :8080  │
                  └──────────┬──────────┘
                             │
                  ┌──────────┴──────────┐
                  │                     │ internal HTTP (HMAC-signed)
                  ▼                     ▼
             PostgreSQL          storage-service
            (Amazon RDS)        Spring Boot :8081
                                        │
                                        ▼
                                Amazon S3 / local disk
```

| Service | Port | Owns |
| :--- | :--- | :--- |
| `file-sync-service` | 8080 | Auth, folder tree, file metadata, versions, delta sync, conflict detection, SSE push, PostgreSQL |
| `storage-service` | 8081 | Chunk SHA-256 verification, content-addressable storage, S3 or local-disk persistence |

## 2. Why Two Services

- **Different scaling axes**: chunk storage is CPU/IO-bound (hashing, streaming to S3); metadata is relational-query-bound. Splitting them lets `storage-service` scale horizontally on upload-heavy load without adding pressure to Postgres.
- **A real network boundary, not a mesh**: the two services talk over synchronous HTTP, authenticated with HMAC-SHA256 request signing (`X-Service-Id`/`X-Timestamp`/`X-Signature`), not a shared JWT — `storage-service` never fields a user token. This is the one distributed-systems boundary the project is built to let you defend (idempotency, partial failure, retries) without the operational cost of a 5+ service mesh.
- **storage-service has no database.** It treats S3 (or local disk) as a key-value store keyed by SHA-256 hash. All relational state — including which chunks belong to which file version — lives in `file-sync-service`'s PostgreSQL.

### Partial failure between the two services

```
file-sync-service ─── HTTP POST chunk ───▶ storage-service ─── PutObject ───▶ S3
        │                                                                     │
        │ [DB transaction fails after this]                           [Chunk saved]
        ▼                                                                     ▼
PostgreSQL rollback                                             Orphan chunk in S3
```

Chunk writes are idempotent (the storage key is the content hash, with `ifNoneMatch: "*"`
on S3 to make the write-if-absent atomic), so a retried upload is always safe. An orphaned
chunk — one written to storage but never referenced by a committed file version — causes no
corruption; it is reclaimed by a scheduled cleanup job on the `file-sync-service` side. There
is deliberately no 2-phase commit or saga: the two failure modes (DB commit fails after
storage write, or storage write fails before DB commit) are both handled by "retry is safe"
rather than by distributed transaction machinery.

## 3. Content-Defined Chunking

Chunks are NOT fixed-size slices of the file. A fixed-size split means inserting a single
byte near the start of a file shifts every chunk boundary after it, so a one-byte edit forces
a full re-upload. Instead, chunk boundaries are placed using a Rabin rolling hash over a
64-byte sliding window: a boundary is declared wherever the hash matches a bit pattern (with
a forced boundary at a max size, so no chunk grows unbounded). Inserting a byte shifts only
the one chunk it lands in — every chunk before and after it still hashes identically, so delta
sync still finds and skips them.

- **Server-side** (`ContentDefinedChunkingStrategy`, Java) is the source of truth: it decides
  chunk boundaries during `completeUpload` validation and cross-checks the client's manifest.
- **Client-side** (`frontend/src/lib/cdc.ts`, TypeScript) runs the identical algorithm (same
  polynomial, same window size, same 64-bit overflow semantics via BigInt) so the browser can
  compute the same boundaries and hashes the server will expect, without a round trip per chunk.
- Delta identity is by **chunk hash**, not chunk index: the client sends its full chunk-hash
  manifest to `POST /upload/delta`, and the server returns the hashes it doesn't already have.
  An index is just an ordering field.

## 4. Upload Flow

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant FSS as file-sync-service (:8080)
    participant SS as storage-service (:8081)
    participant DB as PostgreSQL

    Client->>FSS: POST /api/upload/init (fileId, expectedSize, expectedHash, baseVersion)
    FSS->>DB: Check baseVersion == file.currentVersion
    FSS->>DB: INSERT upload_sessions
    FSS-->>Client: sessionId

    Client->>Client: Compute CDC chunk boundaries + SHA-256 per chunk
    Client->>FSS: POST /api/upload/delta (sessionId, chunk hash manifest)
    FSS->>DB: Record totalChunks on session; diff against existing chunk hashes
    FSS-->>Client: missing chunk hashes

    loop For each chunk the server doesn't have
        Client->>FSS: POST /api/upload/{sessionId}/chunks/{chunkIndex} (binary)
        FSS->>FSS: Verify SHA-256(data) == clientHash
        FSS->>SS: POST /internal/chunks/{hash} (HMAC-signed)
        SS->>SS: Re-verify SHA-256, write if absent (dedup)
        FSS->>DB: INSERT chunk + upload_session_chunks row
    end
    loop For each chunk the server already has (dedup / unchanged from previous version)
        Client->>FSS: POST /api/upload/{sessionId}/chunks/{chunkIndex}/link
        FSS->>FSS: Verify the chunk is actually ACTIVE with matching size (never trust the client's say-so)
    end

    Client->>FSS: POST /api/upload/{sessionId}/complete
    FSS->>DB: Create file_version, link all chunks, bump files.current_version
    FSS-->>Client: SSE push: FILE_UPDATED
```

The `/link` endpoint exists because CDC delta sync is pointless without it: every chunk index
must have a row by the time the session completes, including indices whose content is
byte-identical to the previous version. The endpoint only succeeds against a chunk the server
already has and has verified as `ACTIVE` with matching size — it never creates a chunk record
from an unverified client assertion.

## 5. Delta Sync

1. The client computes CDC chunk boundaries + SHA-256 hashes for the new version locally.
2. It calls `POST /api/upload/delta` with the full chunk-hash manifest and the upload `sessionId`.
3. The server computes `missing = clientHashes − serverHashes` and returns just the missing
   hashes — unchanged chunks need neither a re-upload nor a `/link` call to be identified, only
   to be explicitly linked once identified.
4. Only the genuinely new/changed chunks are uploaded; everything else is linked without
   transferring bytes.

## 6. Conflict Handling

Optimistic concurrency via a version number, not locking:

1. The client sends `baseVersion` (the version it last synced) when starting an upload.
2. If `baseVersion != file.currentVersion`, the write is rejected as a conflict rather than
   silently overwritten.
3. Resolution is explicit: `KEEP_SERVER`, `KEEP_LOCAL`, or `CREATE_CONFLICT_COPY` (branches into
   a separate file so neither version is lost).

## 7. Push Notifications (SSE)

The server needs to tell connected clients "a file changed, go re-fetch it" — a one-directional
notification. The client never sends anything back over this channel. That is a Server-Sent
Events use case, not a WebSocket one: SSE is plain HTTP, reconnects automatically, and needs no
separate protocol (STOMP) or fallback transport (SockJS) to get there. `GET /api/sync/events`
is a long-lived SSE stream per connected user; `file-sync-service` still publishes events to a
Redis pub/sub channel first; the SSE layer just subscribes to Redis and fans out to that user's
connected emitters. Redis stays as the cross-instance backplane — whichever instance handled
the write that produced the event, every instance holding that user's SSE connection still
receives it.

## 8. Database Model

Owned exclusively by `file-sync-service`; `storage-service` has no database connection.

```
users ─┐         devices ─┐        upload_sessions
       │                  │              │
       ▼                  ▼              ▼
     files ──▶ file_versions      upload_session_chunks
                   │
                   ▼
            file_version_chunks ──▶ chunks
```

One database rather than one-per-service: file metadata, version history and chunk references
need ACID relational integrity together, and splitting them would force either a distributed
transaction or eventual consistency for every version creation — for a system of this size,
neither buys anything.

## 9. AWS Deployment

EC2 (both services + Nginx on one `t3.micro`), RDS (Postgres, Single-AZ `db.t3.micro`), and S3
(chunk storage, reached over the free Gateway VPC Endpoint — no NAT Gateway). See
[`aws/AWS_SETUP_GUIDE.md`](aws/AWS_SETUP_GUIDE.md) for the deployment walkthrough and
`aws/cloudformation.yml` for the infrastructure template.

## 10. Key Design Decisions

| Decision | Chosen | Rejected | Why |
| :--- | :--- | :--- | :--- |
| Service split | Exactly 2, by data ownership (relational metadata vs. content-addressable blobs) | Monolith, or 5+ fine-grained services | A real scaling/ownership boundary without mesh overhead |
| Chunking | Content-defined (rolling hash) | Fixed-size chunks | Fixed-size chunking shifts every later chunk on a single-byte insert, defeating delta sync |
| Chunk identity | SHA-256 content hash | Sequential ID | Enables dedup and hash-based delta diffing |
| Inter-service auth | HMAC-signed internal requests | Shared JWT | `storage-service` never needs to understand user identity |
| Real-time updates | Server-Sent Events | WebSocket/STOMP | The channel is one-directional; SSE is the simpler tool for that, with built-in reconnect |
| Binary storage | S3 (CAS) | BLOBs in PostgreSQL | Keeps the relational buffer pool and WAL free of multi-MB binaries |
| Concurrency control | Optimistic (version check) | Pessimistic locking | Conflicts are rare in a sync tool; locking would serialize unrelated uploads |

## 11. Local Development

```bash
./run.sh
```

Starts PostgreSQL + Redis (Docker), `storage-service` (:8081), `file-sync-service` (:8080),
and the React frontend (:5173).

Manual startup:
```bash
docker compose up -d postgres redis
./mvnw -pl storage-service spring-boot:run      # Terminal 2
./mvnw -pl file-sync-service spring-boot:run    # Terminal 3
cd frontend && npm install && npm run dev       # Terminal 4
```

- Web dashboard: `http://localhost:5173`
- API & Swagger: `http://localhost:8080/swagger-ui.html`
- Storage service health: `http://localhost:8081/internal/health`

## 12. Repository Structure

```text
distributed-file-sync/
├── pom.xml                          # Root multi-module Maven parent POM
├── file-sync-service/               # Auth, metadata, versions, sync, conflicts, SSE (:8080)
│   └── src/main/java/com/filesync/
├── storage-service/                  # Content-addressable chunk storage, S3/local disk (:8081)
│   └── src/main/java/com/filesync/storage/
├── frontend/                        # React 18 + Vite dashboard (:5173)
├── aws/                              # CloudFormation template + EC2/deploy scripts
├── nginx/                            # Reverse proxy config for the EC2 deployment
├── run.sh                            # Local multi-service launcher
└── docker-compose.yml                # Local Postgres + Redis + both services
```
