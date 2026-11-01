# Relish — Build & Requirements Specification

A complete specification to rebuild the **Relish** Android app identically, plus everything needed
to compile it into an installable APK using only a phone (via GitHub Actions). Hand this document,
together with the existing source tree in `relish/`, to any capable coding assistant.

The authoritative reference is the existing source code in this repository under `relish/`. This
document describes **what** to build and **how** to compile it; where it and the code disagree, the
code wins.

---

## 1. Business requirements

### 1.1 Product
Relish is a **personal, non-commercial, single-user** Android client for Reddit, visually modelled
on the classic **"Reddit is Fun" (RIF)** app: a dense, information-first list UI rather than a
card-heavy modern feed. It is sideloaded, not published to any store.

### 1.2 Goals
- Browse Reddit (front page, any subreddit, r/all, r/popular, multireddits, user profiles, saved,
  search) with RIF-style density.
- Full **NSFW support** (the user is an adult opting in).
- Account actions when logged in: vote, save, hide, reply, subscribe, read inbox.
- Offline **demo mode** so the whole UI can be used/tested without a Reddit login.
- Import existing data from a **Reddit is Fun backup** (subscriptions, read history, filters,
  settings) and from a subscriptions multireddit link/list.

### 1.3 Non-goals
- No posting automation / bots. All network actions are user-initiated taps.
- No ads, monetisation, analytics, or telemetry.
- No server component. Everything runs on-device.
- Not a general multi-account product; one user, one (optional) account.

### 1.4 Authentication policy (important)
Relish authenticates **only** with the user's **own** Reddit "installed app" OAuth2 client ID,
which the user registers at `reddit.com/prefs/apps` and enters in the app's Settings. The app must
**not** ship a hardcoded client ID, must **not** impersonate another third-party app's credentials,
and must **not** attempt to defeat Reddit's bot/anti-automation checks. If Reddit has not issued the
user a client ID, the app still fully functions in **demo mode** and for any endpoints Reddit serves
without authentication. Do not implement credential-spoofing or challenge-bypass behaviour.

---

## 2. Technology stack

| Concern | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 (restyled to RIF density) |
| Async | Coroutines / Flow |
| DI | Hilt |
| Networking | Retrofit 2 + OkHttp 4 |
| JSON | kotlinx.serialization (plus hand-rolled parsing of Reddit's polymorphic JSON) |
| Local DB | Room |
| Preferences | DataStore (Preferences) |
| Secrets | EncryptedSharedPreferences (AndroidKeyStore AES-256) |
| Media | Media3 / ExoPlayer (HLS + DASH), Coil (images/GIF) |
| Web | AndroidX Browser (Custom Tabs) + WebView |
| Startup perf | androidx.profileinstaller |

Architecture: **MVVM**, single-Activity, `@HiltViewModel` per screen, a single `RedditRepository`
as the data boundary, Navigation-Compose for routing.

### 2.1 Exact dependency versions (`gradle/libs.versions.toml`)
```
agp = "8.7.3"            kotlin = "2.1.0"          ksp = "2.1.0-1.0.29"
hilt = "2.53.1"          hiltNavigationCompose = "1.2.0"
coreKtx = "1.15.0"       lifecycle = "2.8.7"       activityCompose = "1.9.3"
composeBom = "2024.12.01"  navigation = "2.8.5"
room = "2.6.1"           datastore = "1.1.1"       securityCrypto = "1.1.0-alpha06"
retrofit = "2.11.0"      okhttp = "4.12.0"         serialization = "1.7.3"   coroutines = "1.9.0"
media3 = "1.5.0"         coil = "2.7.0"            browser = "1.8.0"
profileinstaller = "1.4.1"  junit = "4.13.2"
```
Gradle wrapper: **8.11.x**. `compileSdk`/`targetSdk` **35**, `minSdk` **26**, `applicationId`
`app.relish`, namespace `app.relish`, JDK **17**, Kotlin JVM target **17**.

---

## 3. Source layout (package `app.relish`)

```
MainActivity.kt            Single activity; catches OAuth redirect deep links.
RelishApp.kt               @HiltAndroidApp; Coil ImageLoader (GIF decoders, caches).
di/AppModule.kt            Hilt providers: Json, OkHttp stacks, Retrofit x2, Room, DAOs, scope.

data/model/Models.kt       Domain models + FeedSource/Sort/CommentSort/TimeFilter enums.
data/remote/
  RedditApiService.kt      ONE Retrofit interface, bound to two base URLs (OAuth + public).
  RedditAuthService.kt     (in same file) token + revoke endpoints.
  NetworkInterceptors.kt   Rate limiter, UA, raw_json, public-.json cookie layer, caching, auth.
  OAuthManager.kt          Installed-app OAuth2: code flow, refresh, app-only grant.
  RedditParser.kt          Turns Reddit "Thing"/"Listing" JSON into domain models.
  RedgifsClient.kt         Resolves redgifs ids to mp4 via their temporary-token API.
data/prefs/
  PreferencesDataStore.kt  All user settings + export/import JSON.
  SecureStore.kt           Encrypted client id / tokens / cookies / redirect URI / device id.
data/local/Database.kt     Room: entities, DAOs, AppDatabase (v2, migration 1->2).
data/repo/RedditRepository.kt   Hybrid routing, actions, visibility rules, backups, account prefs.
data/rif/RifBackupImporter.kt   Imports a Reddit-is-Fun backup zip.
data/demo/DemoData.kt      Offline sample content for demo mode.

ui/theme/Theme.kt          RIF colour palette, density spacing, typography, depth-bar colours.
ui/nav/AppNavigator.kt     Central navigation + link routing (reddit links open natively).
ui/nav/RelishNavHost.kt    NavHost + routes + global snackbar + keep-screen-on.
ui/MainViewModel.kt        App-scoped state: settings, account, subscriptions, messages.
ui/feed/{FeedScreen,FeedViewModel,PostItem}.kt
ui/comments/{CommentsScreen,CommentsViewModel,CommentItem}.kt
ui/media/{MediaViewerScreen,MediaViewerViewModel}.kt
ui/drawer/AppDrawer.kt
ui/settings/{SettingsScreen,SettingsViewModel,FilterScreen}.kt
ui/login/LoginScreen.kt
ui/inbox/InboxScreen.kt
ui/search/SearchScreen.kt
ui/web/WebViewScreen.kt
ui/common/{HtmlText,ClientIdImport}.kt
util/{Connectivity,Format,MediaResolver,Timing}.kt
```

---

## 4. Networking & data

### 4.1 Two transports, one interface
`RedditApiService` declarations back **two** Retrofit instances (same paths work on both hosts):
- `@OAuthApi` → `https://oauth.reddit.com/` with a bearer token (user or app-only).
- `@PublicApi` → `https://www.reddit.com/` with no token; an interceptor appends `.json` to the
  path and attaches the `over18=1` + quarantine/gated opt-in cookie, plus an optional user-supplied
  browser session cookie, so Reddit serves age-gated listings the way the website does.

`RedditRepository.browse{}` picks a transport per request based on a **Content routing** setting
(Auto / Public-JSON / OAuth-only). Logged in → OAuth, with a fall-back to public JSON when OAuth
refuses NSFW (403/404/451) or returns an empty listing; logged out → public JSON, with an app-only
OAuth fall-back when the public layer is refused. Account actions always use OAuth.

> Note: Reddit has progressively restricted both transports for third-party clients. Implement the
> routing exactly as the user's own access permits; do **not** add disguise/bypass logic.

### 4.2 Shared OkHttp stack
- **Rate limiter**: sliding window, max **60 requests/minute**, also backing off on Reddit's
  `x-ratelimit-*` headers.
- `raw_json=1` on GETs; `User-Agent` in Reddit's required format
  `android:app.relish:v<version> (by /u/<username>)`.
- **HTTP response cache** 50 MB; override Reddit's `no-store` to a 60 s max-age; serve stale when
  offline; pull-to-refresh sends `Cache-Control: no-cache`.
- Images/video use a separate OkHttp client (no rate limiter).

### 4.3 OAuth2 (installed app)
Authorization-code flow with `duration=permanent`; refresh-token renewal; `installed_client`
app-only grant for logged-out OAuth. Authorize page tried in this order with a UI "try another"
switch: `ssl.reddit.com/api/v1/authorize.compact`, `www.reddit.com/api/v1/authorize`,
`old.reddit.com/api/v1/authorize.compact`. Token endpoint on `www.reddit.com/api/v1/access_token`
with HTTP Basic `client_id:` (no secret). Redirect URI is user-configurable (default `relish://auth`;
the manifest also accepts `relish://oauth`). The CSRF `state` is persisted so the redirect survives
process death. **No client ID is hardcoded**; it comes from Settings or a build property.

### 4.4 Endpoints (see `RedditApiService.kt`)
Browsing: front page `/{sort}`, `/r/{sub}/{sort}`, `/user/{owner}/m/{multi}/{sort}`,
`/user/{user}/{where}`, `/search`, `/r/{sub}/search`, `/comments/{id}`, `/comments/{id}/_/{comment}`,
`/api/morechildren`, `/r/{sub}/about`, `/subreddits/search`. Sorts: hot/new/top/rising/controversial
(+ best on front page); time filters for top/controversial. Account: `/api/v1/me`,
`/api/v1/me/prefs` (GET+PATCH), `/subreddits/mine/subscriber`, `/api/multi/mine`,
`/message/{where}`, `/api/read_message`, `/api/vote`, `/api/save`/`unsave`, `/api/hide`/`unhide`,
`/api/subscribe`, `/api/comment`.

### 4.5 JSON parsing
Reddit returns polymorphic "Thing"/"Listing" JSON with inconsistent fields (`replies` is `""` when
empty; `edited` is `false` or a timestamp; NSFW thumbnails are placeholder strings). Parse raw
`JsonElement`s (see `RedditParser.kt`) rather than strict DTOs. `MediaResolver.kt` classifies a
post's link into: image / video (reddit HLS+DASH with mp4 fallback) / gif-as-mp4 / gallery (incl.
animated items) / audio / redgifs / imgur / external link, handling crossposts.

---

## 5. Persistence

### 5.1 DataStore settings (`AppSettings`)
Appearance: theme (System/Light/Dark/Pure-black), lastDarkTheme, accentColor, depthColors list,
fontSize (Small/Medium/Large/XL), compactMode, keepScreenOn, leftHanded, showLinkFlair,
showCommentFlair, commentGuideLines, visibleSortTabs. Layout/media: thumbnails
(Always/WiFi-only/Never), postLayout (compact list / large cards), inlinePreviewsInCards,
autoMuteVideos, loopVideos, internalMediaViewer (images), internalVideos, internalAlbums,
internalBrowser. Content/NSFW: showNsfw (default ON), blurNsfw, bypassAgePrompt, blurSpoilers,
routing. History/privacy: markRead, hideReadPosts, saveSearchHistory. Behaviour: defaultSort,
defaultCommentSort, startupSubreddit, compressComments, backGoesHome, quitConfirmation,
autoLoadNextPage, demoMode. Provide `exportJson()` / `importJson()` for backup.

### 5.2 EncryptedSharedPreferences (`SecureStore`)
client id (empty default), redirect URI (default `relish://auth`), OAuth access/refresh tokens +
expiry + isUser flag, username, optional browser session cookie, device id, pending OAuth state.
Never backed up (see `data_extraction_rules.xml`).

### 5.3 Room (`AppDatabase`, version 2)
Entities: `read_posts`, `hidden_posts`, `filters` (type KEYWORD/SUBREDDIT/USER/DOMAIN + value),
`search_history`, `subscriptions` (name, local flag, over18, iconUrl, **favorite** — added in
migration 1→2), `nsfw_shortcuts`. DAOs expose Flows for reactive UI. Read marks are pruned older
than 365 days.

---

## 6. Screens & behaviour (RIF styling)

### 6.1 Theme (`Theme.kt`)
Light-blue app bar (`#CEE3F8`); blue post titles (`#1A1AE6`) turning purple (`#6A3DA8`) once read;
grey flair chip before the title; dense line heights (~1.2×, not Material's ~1.4×); no dividers
between list rows (cards keep them); per-depth comment bar colours (configurable palettes).

### 6.2 Feed (`FeedScreen`, `FeedViewModel`, `PostItem`)
- Light-blue top bar: drawer button, current feed title, refresh, overflow menu; below it a
  **sort tab row** (BEST/HOT/NEW/RISING/TOP/CONTROVERSIAL, user-filterable), with a time popup for
  top/controversial.
- Rows: **left vote column** (compact up/▲ triangle aligned to the title's first line, score, down
  ▼), title line (grey flair chip + blue/purple title + `(domain)`), subtext `N comments  subreddit`,
  **right-edge square thumbnail** (flush, no rounding; `?` placeholder for links, `!` for self
  posts, play/album/audio badges, NSFW blur on API 31+ or a red NSFW pill below). Left-handed mode
  swaps vote column and thumbnail.
- Tap a row → it turns light blue, shows "time by author", and a text action bar
  `share · save · hide · … · comments` (… = Open link / Subreddit / User).
- Infinite scroll (or a Load-more button when auto-load is off); RIF "▲ page N ▼" bars between
  loaded pages. Large-card layout is an alternative with inline previews.
- Visibility filtering applies NSFW master switch, block lists, hidden and (optionally) read posts.
- Optimistic vote/save with rollback on error.

### 6.3 Comments (`CommentsScreen`, `CommentsViewModel`, `CommentItem`)
- Top bar shows post title + `/r/sub` with reply/refresh/overflow; the post renders as a RIF row
  with "posted to …" / "time by author" and selftext; then a grey band `N comments / sorted by X`
  with a sort gear.
- Flattened comment tree with thin coloured depth bars; headers "author  N points  time".
- **Collapse replies by default** (RIF): show top-level comments only, replies folded as
  "N more comments [+]"; tap to expand. Also per-comment collapse (tap header / long-press).
- Per-comment action bar: Upvote, Downvote, Reply, Profile, Context, Copy. "Load more" and
  "continue this thread". Reply posts markdown and inserts the new comment locally.

### 6.4 Media viewer (`MediaViewerScreen`)
Pinch/double-tap zoom images; swipeable galleries (image + video items); ExoPlayer for
v.redd.it (HLS with audio, mp4 fallback), mp4/webm, imgur gifv, redgifs, audio; mute toggle;
auto-mute & loop from settings; YouTube links open the YouTube app.

### 6.5 Drawer (`AppDrawer`)
"Type a subreddit name" box + settings gear; fixed rows: reddit front page (MULTIS button → your
multireddits), popular (FILTER button → filters), all, random; NSFW shortcuts; then subscriptions
sorted favourites-first, each with a ☆ favourite star and a ⋮ menu (Unsubscribe / Add NSFW shortcut
/ Filter). Bottom: "Import subscriptions" (multireddit link / list / **Reddit is Fun backup .zip**)
and "Add NSFW shortcut".

### 6.6 Settings (`SettingsScreen`) — RIF categories, each its own screen
General (homepage, back-goes-home, quit confirmation, auto-load, default sorts); Browser (prefer
external browser; in-app images/videos/albums; auto-mute; loop); Data usage (thumbnails, previews,
clear cache); Content filters (NSFW master, blur NSFW, skip age prompt, blur spoilers, filter
lists); History (mark/hide read, search history, clear buttons); Appearance (theme, text size,
accent, compact, keep-screen-on, cards, left-handed, link flair, sort tabs, comment flair, guide
lines + colours, collapse child comments); Account (login/logout, demo mode, client id, import
client id from file, redirect URI, session cookie, content routing, sync subscriptions); reddit web
preferences (account prefs via `/api/v1/me/prefs` — over_18, NSFW search, flair, messaging, privacy…);
Backup (export/import settings JSON; **import from Reddit is Fun**); About.

### 6.7 Login (`LoginScreen`)
If no client id: a one-time setup screen explaining how to register an installed app (redirect
`relish://auth`) with a field + "import from file" + "try demo mode". Otherwise: in-app WebView
login (redirect intercepted) with a "blank page? try another" page switch, and a "use browser
instead" Custom Tab option. On `relish://auth` (or `relish://oauth`) redirect, `MainActivity`
completes the token exchange.

### 6.8 Inbox (`InboxScreen`), Search (`SearchScreen`), WebView (`WebViewScreen`)
Inbox tabs (all/unread/messages/comment-replies/post-replies/mentions/sent) with mark-read. Search
posts or subreddits (NSFW included), recent-search history, restrict-to-subreddit. In-app WebView
seeds the over18 cookies and routes reddit links natively.

### 6.9 Demo mode (`DemoData`)
When `demoMode` is on, the repository serves built-in sample content and treats actions as local —
covering every post/media type (image, portrait, NSFW-flagged, GIF, gif-as-mp4, mp4-with-audio,
HLS, DASH, gallery with a video item, audio, YouTube, web link, formatted/spoiler/locked/stickied
self posts, a broken image), a second page for infinite scroll, a realistic nested comment thread
with load-more/continue stubs, and a demo inbox. Media points at public test assets (Google sample
videos, Mux/Akamai test streams, Picsum, Wikimedia GIFs, Blender open films).

### 6.10 Reddit is Fun import (`RifBackupImporter`)
Reads RIF's exported `.zip` (`reddits.db`, `threadfilters.db`, `browserhistory.db`, `settings.xml`),
extracting only those entries to the cache dir and opening the SQLite DBs read-only:
- `reddits` rows with `frontpage=1` → local subscriptions (keep favourite/NSFW flags).
- Most-visited NSFW subreddits → NSFW shortcuts.
- `thread_filters` → filters. `uri_history` `thread_id`s → read marks (batched).
- Known `settings.xml` keys → Relish settings (theme, text size, back-goes-home, quit confirm,
  auto-load, external browser, albums, flair, left-handed, collapse child comments, comment sort,
  over_18).

---

## 7. Build & compile to APK

### 7.1 Local build (if you have a computer)
Requirements: JDK 17, Android SDK (`compileSdk`/`platforms;android-35`, build-tools), accept SDK
licences. Then:
```
cd relish
./gradlew assembleRelease      # -> app/build/outputs/apk/release/app-release.apk
./gradlew assembleDebug        # optional debug build
./gradlew testDebugUnitTest    # runs the JUnit tests (parser, media, demo, folding, sub-import)
```
Signing: a fixed **sideload keystore** committed at `app/relish-sideload.keystore`
(store/key alias `relish`, password `relish-sideload`) signs **both** debug and release so each new
build installs over the previous one. The release build enables R8 minify + resource shrink
(`proguard-rules.pro` keeps kotlinx.serialization, Retrofit, Tink). This key is not a secret; it
only identifies "builds of this repo" to Android's package manager. Client ID can be injected at
build time with `-PredditClientId=<id>` (otherwise empty; entered in-app).

### 7.2 Phone-only build via GitHub Actions (the primary method)
Because the owner builds from a phone, **CI produces the APK and publishes it as a GitHub Release.**
Workflow file: `.github/workflows/relish-apk.yml`. On every push under `relish/**` (or manual
dispatch) it:
1. `actions/checkout`, `actions/setup-java@v4` (Temurin 17), accept Android SDK licences
   (`$ANDROID_HOME` is preinstalled on `ubuntu-latest`), `gradle/actions/setup-gradle@v4`.
2. `./gradlew assembleRelease assembleDebug testDebugUnitTest` (piped to a log; a follow-up step
   greps compiler errors on failure so they're easy to find).
3. Rename to `relish-<run#>.apk` (release) and `relish-<run#>-debug.apk`.
4. `actions/upload-artifact` + `softprops/action-gh-release@v2` → a prerelease tagged
   `relish-build-<run#>` with both APKs attached.

To use it from a phone: edit files in the GitHub web/mobile UI, commit, wait for the Actions run to
go green, open the new Release, download `relish-<n>.apk`, and install (allow "install unknown
apps"). Install the **release** APK; the debug one is a fallback.

### 7.3 Install & first run
Install the APK; it is debug-signed for sideloading so Play Protect may warn (expected). On first
run either turn on **demo mode** (no account needed) or, to log in, register your own Reddit
"installed app" (redirect `relish://auth`), paste its client id into Settings › Account, and log in.

---

## 8. How the assistant (Claude) drives GitHub — the commit → build → release loop

The owner works **only from a phone** and cannot run a build. The entire loop is operated by the
coding assistant running in a cloud session that has a checkout of the repo and authenticated
GitHub access. Reproduce this operating model:

### 8.1 What the assistant has
- A working git checkout of the repository in its cloud container, with push access to the owner's
  feature branch (over HTTPS; credentials are injected by the environment — no secret is stored in
  the repo).
- Read access to GitHub's API for Actions and Releases (in this project via the GitHub MCP tools
  prefixed `mcp__github__*`; equivalently the `gh` CLI or REST API). There is **no** `gh` binary in
  this environment — the MCP tools are used instead.

### 8.2 The loop, step by step
1. **Edit** source files in the checkout (the assistant's own file tools), keeping changes scoped to
   `relish/`.
2. **Commit** with the git CLI. Commit messages end with the attribution footer:
   ```
   Co-Authored-By: Claude <noreply@anthropic.com>
   Claude-Session: <session url>
   ```
3. **Push** to the owner's feature branch: `git push -u origin <branch>`. If the push is rejected as
   non-fast-forward (the owner pushed their own commits from the phone/web UI), the assistant
   `git fetch origin <branch>` then `git rebase origin/<branch>` and pushes again. It never
   force-pushes over the owner's commits.
4. The push matches the workflow trigger (`paths: relish/**`), so **GitHub Actions** starts a build
   automatically. No manual dispatch is needed.
5. **Poll the run**: list workflow runs for `relish-apk.yml`, read the latest run's status and
   conclusion. (A background timer is used to wait a few minutes between checks rather than busy-
   polling.)
6. **On success**: read the new **Release** (tag `relish-build-<run#>`) and give the owner the
   release URL + the asset name `relish-<run#>.apk` to download and install on the phone.
7. **On failure**: fetch the failed job's logs, find the compiler/manifest error (the workflow has a
   "Show build errors" step that greps `^e: | Compilation error | FAILED` to the end of the log so
   it is easy to locate), fix the code, and repeat from step 1.

### 8.3 Reading build results (GitHub MCP tool equivalents)
- List runs: `mcp__github__actions_list` (`list_workflow_runs` for `relish-apk.yml`).
- Run detail / conclusion: `mcp__github__actions_get` (`get_workflow_run`).
- Failed-job logs: `mcp__github__get_job_logs` (with `failed_only: true, return_content: true`).
- Releases: `mcp__github__list_releases`.
Any assistant without these tools can use the GitHub REST API or `gh run list` / `gh run view --log`
/ `gh release list` to the same effect.

### 8.4 What the owner does from the phone
- Download/install the APK from the Release page (allow "install unknown apps").
- Optionally edit files or commit directly through GitHub's mobile web UI (which also triggers a
  build). When the owner has pushed their own commits, the assistant rebases onto them (8.2 step 3).
- Send screenshots/feedback; the assistant iterates and pushes a new build.

### 8.5 Branching
Development happens on a long-lived feature branch (here `ccr-400b20b6-ptg5u6`). The owner has also
created ad-hoc branches (e.g. `new`) via the web UI; each push to any branch under `relish/**`
produces its own build and `relish-build-<run#>` release. Keep one branch authoritative to avoid
confusion over which release is current.

## 9. Known constraints (state honestly to any rebuilder)
- Reddit no longer reliably serves third-party clients: self-service key creation at
  `prefs/apps` often returns HTTP 500, the public `.json` layer returns bot-check interstitials to
  non-browser clients, and personal API-access requests are frequently declined. The app is correct;
  what blocks a login is whether Reddit will issue **the user** a client ID. Do not work around this
  by impersonating another app or defeating bot checks — build the legitimate OAuth path and let the
  user supply their own credentials.
- Imgur albums and some external hosts fall back to the browser.
- Build the app assuming the user runs it against their own account at light, interactive volume.
