# Mihon gRPC Service - Implementation Status

## 📊 Progress: Phase 1 Complete (Foundation)

### ✅ What's Been Done

#### 1. Project Structure

```
mihon_grpc_service/
├── build.gradle.kts              ✅ Kotlin/gRPC build config
├── src/main/
│   ├── proto/
│   │   └── manga_service.proto   ✅ Full gRPC API definition
│   ├── kotlin/
│   │   ├── moe/radar/mihon_grpc/
│   │   │   └── state/
│   │   │       └── StatelessState.kt  ✅ In-memory storage
│   │   ├── eu/kanade/tachiyomi/
│   │   │   ├── network/           ✅ OkHttp + rate limiting (copied)
│   │   │   └── source/            ✅ SManga, SChapter models (copied)
│   │   └── suwayomi/tachidesk/
│   │       └── manga/impl/        ✅ Extension utilities (copied)
│   └── AndroidCompat/              ✅ 313 files (copied)
├── README.md                       ✅ Documentation
├── TODO.md                         ✅ Implementation plan
└── STATUS.md                       ✅ This file
```

#### 2. gRPC API Definition

Complete proto file with services:

- ✅ Extension management (list, install, uninstall, update)
- ✅ Source management (list, get)
- ✅ Manga operations (details, search, popular, latest)
- ✅ Chapter operations (list, pages)

All designed for **stateless operation** using URL as primary identifier.

#### 3. Network Layer (Copied from Suwayomi)

- ✅ `NetworkHelper.kt` - OkHttp client factory
- ✅ `RateLimitInterceptor.kt` - Per-source rate limiting
- ✅ `CloudflareInterceptor.kt` - Cloudflare bypass
- ✅ `UserAgentInterceptor.kt` - Custom user agent
- ✅ `PersistentCookieStore.kt` - Cookie handling
- ✅ All other network utilities

#### 4. Tachiyomi Source Models (Copied)

- ✅ `SManga` - Manga data class
- ✅ `SChapter` - Chapter data class
- ✅ `Source`, `CatalogueSource`, `HttpSource` - Source interfaces
- ✅ `Filter`, `FilterList` - Search filtering
- ✅ `MangasPage` - Paginated results

#### 5. AndroidCompat Layer (Copied)

- ✅ Full AndroidCompat directory (313 files)
- ✅ Required for Tachiyomi extensions to run on JVM
- ✅ Preference system stubs
- ✅ Context implementation

#### 6. Extension Utilities (Copied)

- ✅ `PackageTools.kt` - APK → JAR conversion, class loading
- ✅ Extension-related code from Suwayomi
- ✅ Extension GitHub API fetching code

#### 7. In-Memory State Management

- ✅ `StatelessState.kt` - Complete implementation
- ✅ ConcurrentHashMap-based storage for:
    - Extensions metadata
    - Sources metadata
    - Loaded source instances
    - Loaded JAR paths
    - Extension list cache (60s TTL)

### 🚧 What's Next (Phase 2-4)

#### Phase 2: Extension & Source Management

**Files to create:**

1. `ApplicationDirs.kt` - Simple wrapper for extension storage paths
2. `ExtensionManager.kt` - Stateless extension management:
   ```kotlin
   - installExtension(pkgName)
   - uninstallExtension(pkgName)
   - listExtensions()
   - fetchExtensionsFromGitHub()
   ```
3. `SourceManager.kt` - Stateless source management:
   ```kotlin
   - getCatalogueSource(sourceId)
   - listSources()
   - registerSource(source)
   ```
4. `ExtensionAutoUpdater.kt` - Background auto-update job

**Estimated time:** 8-10 hours

#### Phase 3: gRPC Service

**Files to create:**

1. `MangaSourceServiceImpl.kt` - gRPC service implementation
2. `Main.kt` - Server entry point

**Key implementations:**

- All extension RPCs
- All source RPCs
- **Stateless manga operations** (create empty SManga with just URL)
- Chapter operations
- Search/browse streaming

**Estimated time:** 6-8 hours

#### Phase 4: Testing

- Unit tests for extension loading
- Integration tests for full workflow
- Load testing

**Estimated time:** 4-6 hours

## 🎯 Key Design Decisions Made

### 1. URL as Primary Identifier

```kotlin
// Instead of DB-generated manga ID:
val request = GetMangaDetailsRequest(
    sourceId = 6247824327199706550L,
    mangaUrl = "/series/solo-leveling/"  // Just the URL!
)

// Service creates empty SManga:
val sManga = SManga.create().apply { url = mangaUrl }
val details = source.getMangaDetails(sManga)
```

### 2. In-Memory State

- No database dependencies
- ConcurrentHashMap for all storage
- Extensions/sources cached until server restart
- Extension list cached for 60 seconds

### 3. Per-Source Rate Limiting

- Each source instance has its own OkHttp client
- Rate limiting handled by `RateLimitInterceptor`
- Isolated per source (Asura != MangaDex)

### 4. Extension Auto-Update

- Background coroutine job
- Fetches GitHub extension list periodically
- Compares version codes
- Auto-installs updates

## 📁 What Got Copied vs What's New

### Copied from Suwayomi (Can Reuse As-Is)

- ✅ AndroidCompat (313 files)
- ✅ Network layer (OkHttp setup, interceptors)
- ✅ Source models (SManga, SChapter, etc.)
- ✅ Extension utilities (PackageTools, GitHub API)

### Needs Adaptation (Remove DB Dependencies)

- ⚠️ ExtensionManager - Remove all Exposed/SQL code
- ⚠️ SourceManager - Remove all DB lookups
- ⚠️ Extension loading - Use in-memory state instead of DB

### Brand New (Stateless Implementation)

- 🆕 StatelessState.kt ✅ DONE
- 🆕 gRPC service implementation
- 🆕 Extension auto-updater
- 🆕 Server main entry point

## 🔍 Technical Highlights

### Stateless Manga Fetching

```kotlin
// Traditional (Suwayomi) - requires DB:
suspend fun getManga(mangaId: Int): MangaDataClass {
  val record = db.manga.find(mangaId)  // DB lookup
  val sManga = source.getMangaDetails(...)
  db.manga.update(record, sManga)      // DB update
  return ...
}

// Our approach - stateless:
suspend fun getMangaDetails(sourceId: Long, url: String): Manga {
  val source = getSource(sourceId)
  val sManga = SManga.create().apply { this.url = url }
  val details = source.getMangaDetails(sManga)
  return details.toProto()  // Return directly, no DB!
}
```

### Extension Loading Flow

```
1. Fetch extension list from GitHub → Cache in memory
2. Download APK → Save to disk
3. Convert APK to JAR → Use dex2jar
4. Load JAR classes → ClassLoader
5. Instantiate sources → Store in ConcurrentHashMap
6. Each source gets OkHttp client with rate limiting
```

## 📝 Next Steps

To continue implementation:

1. **Create ApplicationDirs wrapper** - Simple path management
2. **Adapt ExtensionManager** - Port from Suwayomi, remove DB
3. **Adapt SourceManager** - Port from Suwayomi, remove DB
4. **Implement gRPC service** - Connect everything
5. **Test end-to-end** - Install extension → query source → fetch manga

See `TODO.md` for detailed checklist.

## 💡 Notes

- This is a **substantial project** (~18-24 hours estimated)
- Foundation is solid - all infrastructure in place
- Main work remaining is adapting Suwayomi code to be stateless
- gRPC service implementation is straightforward once managers are ready
- No database means **must re-download extensions on restart** (unless we add
  optional file persistence later)

## 🤔 Decision Points

Consider before continuing:

1. **Extension persistence**: Should JARs persist across restarts?
    - Current plan: No (re-download on startup)
    - Alternative: Save JARs to disk, scan on startup

2. **Auto-update frequency**: How often to check GitHub?
    - Current plan: Every 60 minutes
    - Configurable?

3. **Concurrency limits**: Limit concurrent source queries?
    - Current plan: No limit (rely on rate limiting)
    - Alternative: Bounded thread pool

4. **Logging level**: Default log verbosity?
    - Current plan: INFO
    - Configurable?

5. **gRPC port**: Which port to use?
    - Suggestion: 50051 (standard gRPC port)
