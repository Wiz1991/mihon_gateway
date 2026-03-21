# Implementation TODO

## Phase 1: Foundation ✅ COMPLETE

### Project Setup

- [x] Create Kotlin/Gradle project structure
- [x] Define gRPC proto file with all services
- [x] Setup build.gradle.kts with all dependencies
- [x] Copy AndroidCompat layer (313 files)
- [x] Copy network layer (OkHttp, interceptors, rate limiting)
- [x] Copy Tachiyomi source models (SManga, SChapter, etc.)
- [x] Create StatelessState in-memory storage

## Phase 2: Extension Management 🚧 IN PROGRESS

### Extension Loading

- [ ] Create ApplicationDirs wrapper (for extension storage paths)
- [ ] Adapt ExtensionGithubApi.kt (fetch extensions from GitHub)
- [ ] Adapt PackageTools.kt:
    - APK downloading
    - APK → JAR conversion (dex2jar)
    - Extension class loading
    - Source instantiation
- [ ] Create ExtensionManager:
  ```kotlin
  object ExtensionManager {
    suspend fun installExtension(pkgName: String)
    suspend fun uninstallExtension(pkgName: String)
    suspend fun updateExtension(pkgName: String)
    suspend fun listExtensions(): List<Extension>
    suspend fun fetchExtensionsFromGitHub()
  }
  ```

### Source Loading

- [ ] Create SourceManager:
  ```kotlin
  object SourceManager {
    fun getCatalogueSource(sourceId: Long): CatalogueSource?
    fun listSources(): List<Source>
    fun registerSource(source: CatalogueSource)
    fun unregisterSource(sourceId: Long)
  }
  ```
- [ ] Implement source caching strategy
- [ ] Handle source instantiation from loaded JARs

### Auto-Update System

- [ ] Create ExtensionAutoUpdater:
  ```kotlin
  class ExtensionAutoUpdater(intervalMinutes: Long) {
    fun start()
    fun stop()
    private suspend fun checkForUpdates()
    private suspend fun installUpdates()
  }
  ```

## Phase 3: gRPC Service Implementation 📋 TODO

### Service Skeleton

- [ ] Create MangaSourceServiceImpl.kt
- [ ] Implement extension RPCs:
    - `listExtensions()`
    - `installExtension()`
    - `uninstallExtension()`
    - `updateExtension()`
- [ ] Implement source RPCs:
    - `listSources()`
    - `getSource()`

### Manga Operations (Stateless!)

- [ ] Implement `getMangaDetails(sourceId, url)`:
  ```kotlin
  // Create empty SManga with JUST URL
  val sManga = SManga.create().apply { url = mangaUrl }
  val details = source.getMangaDetails(sManga)
  // Return details without storing in DB
  ```
- [ ] Implement `searchManga(sourceId, query, page)`:
  ```kotlin
  // Stream results as they come
  val results = source.fetchSearchManga(page, query, filters)
  results.mangas.forEach { manga ->
    responseObserver.onNext(MangaResult(manga, results.hasNextPage))
  }
  ```
- [ ] Implement `getPopularManga(sourceId, page)`
- [ ] Implement `getLatestManga(sourceId, page)`

### Chapter Operations

- [ ] Implement `getChapterList(sourceId, mangaUrl)`:
  ```kotlin
  // Create empty SManga with URL
  val sManga = SManga.create().apply { url = mangaUrl }
  val chapters = source.fetchChapterList(sManga)
  // Return chapter list
  ```
- [ ] Implement `getPageList(sourceId, chapterUrl)`:
  ```kotlin
  val sChapter = SChapter.create().apply { url = chapterUrl }
  val pages = source.fetchPageList(sChapter)
  // Return page URLs
  ```

### Server Setup

- [ ] Create Main.kt with gRPC server:
  ```kotlin
  fun main() {
    val server = ServerBuilder
      .forPort(50051)
      .addService(MangaSourceServiceImpl())
      .build()
      .start()

    server.awaitTermination()
  }
  ```

## Phase 4: Testing & Refinement 📋 TODO

### Unit Tests

- [ ] Test extension loading
- [ ] Test source caching
- [ ] Test rate limiting
- [ ] Test stateless manga fetching

### Integration Tests

- [ ] Test full workflow: install extension → query source → fetch manga
- [ ] Test auto-update mechanism
- [ ] Test concurrent requests to multiple sources

### Performance

- [ ] Load testing with multiple sources
- [ ] Memory profiling (extensions in memory)
- [ ] Rate limit verification

## Key Files to Create/Adapt

### From Suwayomi (Adapt - Remove DB)

1. `ExtensionGithubApi.kt` - Fetch extensions from GitHub ✅ (copied)
2. `PackageTools.kt` - APK handling, JAR conversion, class loading ✅ (copied)
3. `Extension.kt` - Installation logic (remove DB operations)
4. `GetCatalogueSource.kt` - Source loading (remove DB lookups)

### New Files (Stateless Implementation)

1. `StatelessState.kt` - In-memory storage ✅ DONE
2. `ExtensionManager.kt` - Extension management without DB
3. `SourceManager.kt` - Source management without DB
4. `MangaSourceServiceImpl.kt` - gRPC service implementation
5. `ExtensionAutoUpdater.kt` - Background auto-update job
6. `Main.kt` - Server entry point

## Challenges & Solutions

### Challenge: Extension metadata storage

**Solution**: Use `ConcurrentHashMap<String, ExtensionMetadata>` in
StatelessState

### Challenge: Source instance caching

**Solution**: Use `ConcurrentHashMap<Long, CatalogueSource>` - never evict!

### Challenge: Finding source metadata without DB

**Solution**: Parse it from loaded extension classes, store in memory

### Challenge: Auto-updates without DB persistence

**Solution**: Re-fetch extension list from GitHub on restart, mark as "needs
update"

### Challenge: Multiple sources with different rate limits

**Solution**: Each source gets its own OkHttp client instance (already handled
by extensions)

## Estimated Remaining Work

- **Phase 2**: ~8-10 hours (extension/source management)
- **Phase 3**: ~6-8 hours (gRPC service implementation)
- **Phase 4**: ~4-6 hours (testing & refinement)
- **Total**: ~18-24 hours

## Notes

- Extensions are loaded as JAR files and kept in memory
- Each source has isolated rate limiting via its OkHttp client
- No persistence - extensions must be re-downloaded on restart
- Could add optional file-based persistence for extension JARs later
