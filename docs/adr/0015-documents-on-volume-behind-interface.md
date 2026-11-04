# ADR 0015: Documents on a volume behind a DocumentStore, streamed through the API

Status: accepted (plan decision D15)

## Context
Claim documents are sensitive. Every download needs an access check and an audit record, and uploads must not be able to run code in a browser or fill the disk through one request.

## Decision
Bytes are stored by a `DocumentStore` interface; the implementation writes to a directory on the `claims-data` volume under a generated key (never the client's file name). Downloads stream through `claims-service` after an ownership or market check and are logged as an activity. Uploads are limited to 10 MB (nginx and the API), checked by magic bytes rather than declared type, and served as `attachment` with `nosniff`. A claim holds at most 50 documents; uploads lock the claim row so parallel uploads cannot pass the cap.

## Consequences
- Authorisation and audit stay in one place.
- Costs: all document traffic goes through the JVM, there is no malware scanning (stated gap), and one claimant can still fill the volume by creating many claims (no per-claimant limit; stated gap).
- Production answer: an object store with presigned URLs and a scan step; the interface is the seam for it.

## Alternatives rejected
- **S3 plus presigned URLs.** Adds MinIO and bypasses the audit path.
- **BLOBs in the database.** Bloats backups.

## Where it is in the code
- `services/claims-service/src/main/java/com/chubb/claims/document/DocumentStore.java:7`, `FilesystemDocumentStore.java:18`, `FileTypes.java`.
- Tests: `DocumentApiTest.contentIsCheckedByMagicBytesNotByDeclaredType` (`:157`), `overTenMegabytesIs413ProblemJson` (`:175`), `filenameCannotEscapeAndStorageKeyIsNotDerivedFromIt` (`:187`); `DocumentConcurrencyTest`.
