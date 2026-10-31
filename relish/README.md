# Relish: a "Reddit is Fun"-style client

Relish is a single-user Reddit client for personal, non-commercial use. It is modelled on the
dense layout of the legacy *Reddit is Fun* (RIF) app and fully supports NSFW content.

**Stack:** Kotlin 2.1, Jetpack Compose (Material 3 at RIF density), Coroutines/Flow, Hilt,
Retrofit + OkHttp, kotlinx.serialization, Room, DataStore, EncryptedSharedPreferences, Media3
(ExoPlayer) and Coil.

## Build

```
cd relish
./gradlew assembleDebug        # or assembleRelease (signed with the debug key for sideloading)
```

You need the Android SDK (compileSdk 35) and JDK 17. The app runs on minSdk 26 (Android 8.0) and up.

APKs are built by GitHub Actions on every push (`.github/workflows/relish-apk.yml`) and published as
releases. Install `relish-N.apk`, the R8-optimised release build. `relish-N-debug.apk` installs
alongside it, as `app.relish.debug`, for troubleshooting. Both are signed with the fixed sideload
key in `app/relish-sideload.keystore`, so every new build installs over the previous one.

## First run

* **Anonymous browsing works straight away.** It uses the web `.json` layer with the over18 cookie,
  so you don't need an account or an API key.
* **You need your own client ID.** Create an app of type **installed app** at
  <https://www.reddit.com/prefs/apps> with the redirect URI `redditisfun://auth`, then paste its
  client ID into the login screen or *Settings › Account*, or import it from a text file. You can
  also build one in with `-PredditClientId=…`. Reddit now often blocks logged-out access from apps,
  so logging in is required in practice.

## Demo mode

To try the app without a client ID, turn on *Settings › Account › Demo mode*, or tap **Try demo
mode** in the drawer or on the login screen. Feeds, comments, search and the inbox are then served
from built-in sample content, and nothing is sent to Reddit. The sample posts cover:

* landscape and portrait images, animated GIFs, and a GIF-style looping mp4;
* an mp4 with sound, HLS and DASH streams, an mp3, and galleries with mixed images and video;
* YouTube links, which open in the YouTube app;
* web links, formatted text posts, and NSFW, spoiler, stickied and locked posts;
* a broken image, to show the error state;
* a second page of posts, to test infinite scroll.

Comments include a nine-level thread, moderator and OP comments, "load more" and "continue this
thread" stubs, and hidden and negative scores.

The media uses public test assets: Google sample videos, Mux and Akamai test streams, Picsum
photos, Wikimedia GIFs and the Blender Foundation's open films.

## Importing from Reddit is Fun

In Reddit is Fun, go to *Settings › Backup › Export settings* (or *Export settings AND HISTORY*),
then import the `.zip` in Relish from the drawer (*Import subscriptions › From Reddit is Fun
backup*) or from *Settings › Backup*. Relish reads RIF's `reddits.db`, `threadfilters.db`,
`browserhistory.db` and `settings.xml`, and imports:

* subscriptions (`frontpage = 1`), with their favourite and NSFW flags;
* your most-visited NSFW subreddits, as NSFW shortcuts;
* filters;
* read marks from the post history;
* the matching settings: theme, text size, back goes home, quit confirmation, auto-load, external
  browser, albums, flair, left-handed mode, collapse child comments, comment sort and over 18.

## Hybrid network engine (NSFW handling)

`RedditApiService` is a single Retrofit interface backed by two transports, because Reddit serves
the same paths from both hosts:

| Transport | Base URL | Auth | Used for |
|---|---|---|---|
| `@OAuthApi` | `oauth.reddit.com` | Bearer token (user, or installed-client app-only token) | Account actions, plus browsing when logged in |
| `@PublicApi` | `www.reddit.com` | None. `PublicJsonInterceptor` appends `.json` and sends `over18=1`, the quarantine/gated opt-in `_options` cookie and an optional browser session cookie | NSFW browsing that the third-party OAuth API suppresses |

`RedditRepository.browse()` picks a transport for each request based on *Settings › Content routing*:

* **Auto** (default): uses OAuth when you're logged in. It retries on the public JSON layer when
  OAuth refuses (403/404/451) or returns a suspiciously empty subreddit, multireddit or search
  listing. Logged out, it uses public JSON.
* **Always web JSON**: every read goes through public JSON, and OAuth is used only for actions.
* **OAuth only**: never touches the public endpoints.

At login, if *Show NSFW* is on, the app also PATCHes the account preferences (`over_18`,
`search_include_over_18`) so that OAuth listings stop dropping NSFW posts. Searches always send
`include_over_18=on`. The in-app WebView is pre-seeded with the same age-gate cookies.

Both transports share one OkHttp stack:

* a **60 requests/minute sliding-window rate limiter**, which also backs off when Reddit's
  `x-ratelimit-*` headers say the budget is spent;
* `raw_json=1`;
* a **50 MB HTTP response cache** that overrides Reddit's `no-store` with a 60 s max-age and serves
  stale responses offline. Pull-to-refresh sends `Cache-Control: no-cache`.

Images and video are fetched from the CDNs on a separate client that doesn't go through the rate
limiter.

The client ID, OAuth tokens and session cookie are stored in **EncryptedSharedPreferences**
(AES-256 keys backed by the Android Keystore) and are excluded from backup.

## Feature map

* **Drawer:** your profile and karma, Inbox (with an unread badge), Saved, Search, Settings and
  NSFW shortcuts. You can pin subs as shortcuts, and NSFW subs you're subscribed to are listed
  automatically. It also has multireddits and your subscriptions, with a quick-filter field that
  doubles as "go to r/…".
* **Top bar:** the current feed and sort, a sort dropdown (Hot/New/Top/Rising/Controversial, with
  time filters for Top and Controversial) and Refresh. The overflow menu has Search, Night mode,
  Subscribe, Add to NSFW shortcuts, Filter sub and Settings.
* **Feed rows:** a square thumbnail on the left (blurred, or a red NSFW pill if blurring is on), a
  bold title with an inline red **NSFW** badge and the domain, and a line with score, comments,
  author and age. Tapping a row shows the RIF action bar: Upvote, Downvote, Subreddit, Comments,
  Share, Hide, Save and Open link. Tapping the thumbnail opens the media viewer or browser
  directly. Large-card layout is also available.
* **Comments:** a flattened tree with thin depth bars in your chosen colours. Tap a comment for
  the action bar (Upvote, Downvote, Reply, Profile, Context, Copy). Tap the header or long-press to
  collapse a subtree. "Load more" and "continue thread" both work, and you can change the sort.
* **Media viewer:** pinch and double-tap zoom on images, swipeable galleries, and ExoPlayer for
  v.redd.it (HLS with audio, falling back to the silent mp4), mp4/webm, imgur gifv, Redgifs
  (resolved through their temporary-token API) and audio files. There's a mute toggle, and
  auto-mute and loop follow your settings.
* **Settings**, grouped the way RIF groups them:
  * *Account:* client ID, cookie, routing and enabling NSFW on the account.
  * *Appearance:* Light, Dark, Pure Black or System theme; accent colour; depth palettes or custom
    hex colours; four font sizes; compact mode.
  * *Post layout & media:* thumbnails Always, Wi-Fi only or Never; cards or list; auto-mute; loop;
    internal viewer and browser.
  * *Content filtering & NSFW:* the Show NSFW master switch (on by default), Blur NSFW, skipping the
    age prompt, Blur spoilers, and keyword, subreddit, user and domain filters stored in Room.
  * *History & privacy:* read marks stored in Room that persist across restarts, hiding read
    posts, and buttons to clear search history, read history and the cache.

## Source layout

```
app/src/main/java/app/relish/
├── data/remote/RedditApiService.kt   (a) Retrofit interface: OAuth + .json fallback, token endpoint
├── data/remote/NetworkInterceptors.kt rate limiter, over18 cookie/.json rewrite, OAuth, caching
├── data/remote/OAuthManager.kt       installed-app OAuth2 (code flow, refresh, app-only grant)
├── data/remote/RedditParser.kt       Listing/Thing JSON → models
├── data/prefs/PreferencesDataStore.kt (b) every RIF preference + NSFW toggles
├── data/prefs/SecureStore.kt         EncryptedSharedPreferences for tokens/cookies
├── data/local/Database.kt            Room: read posts, hidden, filters, search history, subs, shortcuts
├── data/repo/RedditRepository.kt     hybrid routing, actions, visibility rules
├── ui/feed/PostItem.kt               (c) dense RIF row, NSFW badge/blur, action bar
├── ui/comments/CommentItem.kt        (c) depth bars, collapse, inline comment actions
├── MainActivity.kt, ui/nav/*, ui/drawer/AppDrawer.kt   (d) activity, navigation, drawer
└── ui/{feed,comments,media,web,login,settings,inbox,search}/  screens + ViewModels
```

## Known limitations

* Imgur *albums* (`/a/…`) open in the browser, because they need an Imgur API key.
* Logged-out browsing uses Reddit's public `.json` pages and only works while Reddit serves them
  to apps. Reddit now often answers with a browser check instead, and Relish deliberately doesn't
  try to get around that. Logging in (OAuth with your own client ID) is the reliable way to browse,
  NSFW included via your account's 18+ setting.
* Submitting new posts and sending private messages aren't implemented. Replying to posts and
  comments is.
