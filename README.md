# Mihon gRPC Service

A stateless gRPC service for querying manga sources using Mihon/Tachiyomi
extensions.

## Architecture

This service provides manga source operations **without any database
dependencies**:

- Extension loading and management
- Source querying with URL-based identifiers
- Per-source rate limiting
- Auto-updating of extensions

## Status

### ✅ Completed

- [x] Project structure created
- [x] gRPC proto definitions (`manga_service.proto`)
- [x] Build configuration (`build.gradle.kts`)
- [x] AndroidCompat layer copied (313 files for extension compatibility)
- [x] Network layer copied (OkHttp, rate limiting, Cloudflare handling)
- [x] Source model files copied (SManga, SChapter, etc.)
- [x] StatelessState in-memory storage

### 🚧 In Progress

- [ ] Extension loading utilities
- [ ] Source management
- [ ] gRPC service implementation

### 📋 TODO

- [ ] Create simplified ExtensionManager (no DB)
- [ ] Create simplified SourceManager (no DB)
- [ ] Implement MangaSourceService gRPC endpoint
- [ ] Implement manga detail fetching (URL-only)
- [ ] Implement chapter list fetching
- [ ] Implement search/browse operations
- [ ] Extension auto-updater background job
- [ ] Testing and validation

## Key Differences from Suwayomi

| Feature        | Suwayomi                       | This Service                       |
|----------------|--------------------------------|------------------------------------|
| Storage        | PostgreSQL database            | In-memory (ConcurrentHashMap)      |
| Manga ID       | Database-generated integer     | URL string (from source)           |
| State          | Stateful (persists everything) | Stateless (ephemeral)              |
| API            | GraphQL + REST                 | gRPC                               |
| Manga fetching | Requires DB manga record       | Creates empty SManga with just URL |

## Usage Example

```kotlin
// Get manga details (stateless - URL only!)
val request = GetMangaDetailsRequest.newBuilder()
    .setSourceId(6247824327199706550)  // Asura Scans
    .setMangaUrl("/series/solo-leveling/")  // Just the URL!
    .build()

val manga = stub.getMangaDetails(request)
// Returns: title, author, description, etc.
```

## Dependencies

- gRPC Kotlin
- OkHttp 5.x (with rate limiting)
- AndroidCompat layer (for Tachiyomi extensions)
- Kotlin coroutines
- No database required!

## Next Steps

See `TODO.md` for detailed implementation tasks.
