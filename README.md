# Mihon Gateway

A stateless gRPC service for querying manga sources using [Mihon](https://github.com/mihonapp/mihon)/Tachiyomi extensions. Built on top of [Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server)'s AndroidCompat layer to run Android-based extensions on the JVM.

## Features

- **Stateless** -- No database required. Manga and chapters are identified by URL, not database IDs.
- **gRPC-Web API** -- Full gRPC-Web support with CORS via [Armeria](https://armeria.dev), enabling browser clients.
- **Dynamic Extension Loading** -- Installs Mihon/Tachiyomi extensions from APK, converts to JAR, and loads at runtime.
- **Per-Source Rate Limiting** -- Isolated OkHttp clients with rate limiters per source.
- **Authentication** -- Cookie and localStorage management for sources requiring login.
- **Source Preferences** -- Get and set source-specific configuration via gRPC.
- **Browser Fallback** -- Playwright-based headless Chromium for JavaScript-heavy sources.

## Requirements

- JDK 21+
- Docker (for containerized deployment)

## Quick Start

### Docker Compose (recommended)

```bash
docker compose up -d
```

The service will be available on port `50051`.

### From Source

```bash
./gradlew run
```

#### CLI Options

```
--port <port>    gRPC server port (default: 50051)
--help           Show help
```

## API

The service exposes a single `MangaSourceService` with the following RPCs:

| Category | RPC | Description |
|---|---|---|
| Extensions | `ListExtensions` | List available and installed extensions |
| | `InstallExtension` | Install an extension by package name |
| | `UninstallExtension` | Remove an installed extension |
| | `UpdateExtension` | Update an extension to latest version |
| Sources | `ListSources` | List all loaded sources |
| | `GetSource` | Get source metadata by ID |
| Manga | `GetMangaDetails` | Fetch manga details by source ID + URL |
| | `SearchManga` | Search a source by query string |
| | `GetPopularManga` | Browse popular manga from a source |
| | `GetLatestManga` | Browse latest manga from a source |
| Chapters | `GetChapterList` | Get chapters for a manga by URL |
| | `GetPageList` | Get page image URLs for a chapter |
| Preferences | `GetSourcePreferences` | Get configurable preferences for a source |
| | `SetSourcePreference` | Update a source preference value |
| Auth | `SetCookies` | Set cookies for a source's domain |
| | `GetCookies` | Get stored cookies for a source |
| | `ClearCookies` | Clear cookies for a source |
| | `SetLocalStorageItem` | Set a localStorage key-value for a source |
| | `GetLocalStorageItems` | Get all localStorage items for a source |
| | `ClearLocalStorage` | Clear localStorage for a source |

See [`manga_service.proto`](src/main/proto/manga_service.proto) for full message definitions.

## Architecture

```
Client (gRPC-Web) --> Armeria Server --> MangaSourceService
                                              |
                                    +---------+---------+
                                    |                   |
                              ExtensionManager    SourceManager
                                    |                   |
                              APK -> JAR          Loaded Sources
                              (dex2jar)           (in-memory)
```

Unlike Suwayomi-Server which uses a database for persistence, Mihon Gateway operates entirely in-memory. Each request creates a lightweight object with just a URL, fetches data from the source, and returns it directly.

## Tech Stack

- **Kotlin** with coroutines
- **Armeria** + gRPC-Kotlin for the server
- **OkHttp 5** for HTTP requests
- **Koin** for dependency injection
- **Playwright** for headless browser fallback
- **AndroidCompat** layer (from Suwayomi) for running Android extensions on JVM

## Acknowledgements

This project builds on the work of:

- [Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server) -- AndroidCompat layer and extension loading utilities
- [Mihon](https://github.com/mihonapp/mihon) -- Source API and extension ecosystem
- [Tachiyomi](https://github.com/tachiyomiorg/tachiyomi) -- Original manga reader and extension framework

## License

This project is licensed under the Mozilla Public License 2.0 -- see the [LICENSE](LICENSE) file for details.
