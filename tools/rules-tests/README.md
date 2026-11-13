# LostLink – Firestore Security Rules Tests

Jest-based test suite for `firestore.rules` and `firestore.indexes.json` using `@firebase/rules-unit-testing` against the real Firebase Auth and Firestore Emulators.

## Prerequisites

1. **Java 21+** (for Firestore Emulator) and **Node.js 20+**.
2. Install dependencies (includes `firebase-tools` locally):
   ```powershell
   npm --prefix tools/rules-tests ci
   ```

## Run tests

From repository root (starts Auth `127.0.0.1:9099` and Firestore `127.0.0.1:8088` emulators automatically):

```powershell
npx --prefix tools/rules-tests firebase emulators:exec --only auth,firestore --project demo-lostlink --config firebase.emulators.json "npm --prefix tools/rules-tests test"
```

Or if emulators are already running on `127.0.0.1:8088`:

```powershell
npm --prefix tools/rules-tests test
```

## Test coverage (`docs/Contract-B0-Schema.md` & B0 Matrix)

| File | Coverage |
| --- | --- |
| `tests/posts.test.js` | `B0-T01`, `B0-T02`, `B0-T03`, `B0-T06`, `B0-T08`, canonical taxonomy/area/time/media/contact checks, real atomic `writeBatch()` create/update/status/delete+tombstone, query rules, restored `VISIBLE` zero-leak check (`§6`) |
| `tests/events.test.js` | `B0-T02`, `PersistedPostEvent` strict shape (`searchJob`/`notifyJob`/`mediaCleanupJob` initial maps, no top-level `status`), `{postId}_v{postVersion}` ID rule, anti-spoof `authorId`/`changeType`/`postVersion`/immutability |
| `tests/reports.test.js` | `B0-T15`, `reports/{postId}_{reporterUid}` `PENDING` creation, non-existent/`HIDDEN`/self post rejection, duplicate prevention, owner-only read, immutability |
| `tests/accounts.test.js` | `B0-T07`, `DELETING`/`DELETED`/malformed `account_states` write lock vs owner read preservation |
| `tests/collections.test.js` | Strict access controls for `post_moderations`, `admin_moderation_notes`, `post_embeddings`, `post_reservations`, `media_assets`, `post_tombstones`, `users`, `saved_posts`, and catch-all deny |
