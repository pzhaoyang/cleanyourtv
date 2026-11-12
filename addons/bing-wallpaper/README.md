# Bing Wallpaper

A screensaver that shows Bing's picture of the day, kept as the last **seven days** so the TV has a new one
each day without downloading on every wake-up. Each picture is held still for a minute (adjustable) and then
crossfades to the next; the oldest day is deleted when an eighth arrives.

On cleanyourtv's Home it is also a module card, **Bing Daily** ("A Week of Bing Picture"): press OK on it to
start the slideshow at once, Back to leave, and hold OK to open the settings.

- Package: `com.gathin.bingwall`
- Chinese pictures: the feed asks for `mkt=zh-CN`, so the titles and places Bing gives are in Chinese. With
  `Show the picture's title and place` on, they appear under the picture.
- Tested on: not yet on a TV (see below).

## How it behaves

| Situation | What happens |
|---|---|
| First run, network up | It fetches Bing's last seven days in one request, then shows them newest first |
| First run, no network | It says so on screen and keeps trying when the screensaver next starts |
| Later runs | Pictures are on the TV already, so it shows them at once and refreshes in the background |
| The week has moved on | Refreshed in the background, once the newest picture is a week old. Bing publishes a market's picture for the day before, so "today's picture" is never required; a refresh on every wake-up would put the TV on the network each time |
| A day already cached | Not downloaded again |
| Bing unreachable | The pictures already on the TV keep showing; nothing is deleted |
| Bing answers with fewer than seven days | The pictures already on the TV fill the gap instead of being deleted |
| More than seven days on the TV | Trimmed to the newest seven on every start, so the folder cannot drift above the limit |
| A picture will not decode | Skipped; after one full pass of bad files it stops and says so rather than looping |
| The clock is wrong | A picture dated in the future is not treated as stale, so a wrong clock cannot cause a fetch on every wake-up |

One small JSON request and up to seven JPEG downloads per refresh. Nothing else leaves the TV, and no
account or API key is involved.

## Where the pictures live

`/sdcard/Android/data/com.gathin.bingwall/files/bing/` — one `20261027.jpg` per day, plus a `captions.txt`
holding the Chinese title and place for each. The folder is the app's own, so it goes away when the app does.

## Settings

Reached from the TV's screensaver menu (*Settings > Screen saver > Bing wallpaper > settings*), or by holding
OK in the card's slideshow:

- **Seconds per picture**: 15, 30, 60 (the default), 120 or 300.
- **Show the picture's title and place**: off by default; the text wanders a little so it does not burn in.
- **Refresh now**: fetches in the background and says how many pictures are on the TV afterwards.

## Build and install

JDK 17 and Android SDK 34, as for the launcher. From the repo root:

```powershell
cd addons\bing-wallpaper
gradlew.bat assembleRelease
cd ..\..
tools\venv\Scripts\python.exe -I tools\tv.py --push addons\bing-wallpaper\app\build\outputs\apk\release\app-release.apk /data/local/tmp/addon.apk
tools\venv\Scripts\python.exe -I tools\tv.py "pm install -r /data/local/tmp/addon.apk; rm -f /data/local/tmp/addon.apk"
```

Press Home: the launcher asks once whether to show the card.

## Use it as the screensaver

*Settings > Device Preferences > Screen saver > **Bing wallpaper*** (the menu's name depends on the TV), or:

```powershell
tools\venv\Scripts\python.exe -I tools\tv.py "settings put secure screensaver_components com.gathin.bingwall/.BingDream"
```

To see it without waiting, start the screensaver now:

```powershell
tools\venv\Scripts\python.exe -I tools\tv.py "am start -n com.android.systemui/.Somnambulator"
```

### On firmware without the AOSP dreams service (Xiaomi/Redmi MiTV)

Neither of the two steps above can work there: `service list` has no `dreams`, and the firmware's own power
manager sends `com.duokan.intent.action.show.screensaver` instead of starting a `DreamService`. A
`DreamService` such as `BingDream` therefore never runs, whatever `screensaver_components` says. Point the
launcher at the card's screen instead, and it opens it on that broadcast:

```xml
<string name="screensaver_component" translatable="false">com.gathin.bingwall/.WallActivity</string>
```

It is started with the extra `screensaver=true`, which is what tells `WallActivity` to leave on any key
(as the card it leaves on Back, and holding OK opens its settings).

Xiaomi's own screensaver app, `com.mitv.screensaver` (installed as `/system/priv-app/AodTVSmartScreenSaver`),
gets the same broadcast addressed at itself and answers it by starting `RenderActivity`, whatever its own
settings say: an app cannot switch that off, since the permission it needs is
`CHANGE_COMPONENT_ENABLED_STATE`. So the launcher replies with `com.duokan.intent.action.exit.screensaver`
first, which is the action `MessageReceiver` itself uses to leave, and opens these pictures a moment later.
Two things follow:

- `screensaver_id` is that app's own re-entry guard, not a user switch: `RenderActivity.onCreate` writes it
  to `0` and restores it on exit. Writing it from an app fails anyway (`IllegalArgumentException: You cannot
  keep your settings in the secure settings`), so leave it alone.
- What is on screen is decided by which activity opens last, so the launcher waits for the exit to land
  before starting these pictures. Nothing has to be turned off in the TV's own menus.

To hand the idle screen back to Xiaomi's clock, clear `screensaver_component` in `config.xml` (or use the
`Screensaver` row in launcher settings, which does the same at runtime) and install the launcher again.
Xiaomi's clock then takes the idle screen back with no further change.

## Permissions

- **`INTERNET`**: the screensaver downloads the feed and the pictures from `www.bing.com` over HTTPS. Nothing
  else leaves the TV, there is no analytics and no account.
- **`ACCESS_NETWORK_STATE`**: only to know whether a fetch is worth starting; the app never reads anything
  about the network beyond that.
- No storage permission: the pictures live in the app's own folder and are removed with it.
- No background service and no boot receiver: it downloads only while the screensaver or the card is showing.

## Remove

```powershell
tools\venv\Scripts\python.exe -I tools\tv.py "pm uninstall com.gathin.bingwall"
```

If it was the screensaver, pick another one in Settings.

## Change the look

`Scene.java` holds the fade time (`FADE_MS`), the burn-in wander (`NUDGE_DP`) and the caption styling.
`BingCache.java` holds which feed is fetched (`BingDay.FEED`), how many days are kept (`KEEP`) and how old a
cache may be before it is refreshed (`STALE_MS`). `Options.java` holds the default seconds per picture.

## Notes

- The pictures are Bing's own; check that your use of them fits Bing's terms before showing them in public.
- `Bing Daily` is a card label, so it has to stay within the module-card rules: ASCII, no reserved words.
  Changing it in an update makes the TV owner answer the prompt again.
- The caption is `A Week of Bing Picture`, 22 of the 32 characters a caption may have. Label and caption
  together are 32 characters, and the name line under a focused tile shows about 26 on a four column grid, so
  the end of the caption is cut with an ellipsis there; the dock's shelf shows the label alone.
- Not yet tried on a real TV. The build and the unit tests (`gradlew testReleaseUnitTest`) pass, and it was
  run on an Android TV 9 (API 28) emulator: the card, the screensaver, the download, the 60 second change and
  the walk through the week all work there. The first run on a set should check the card prompt, the
  screensaver wake-up and a day's rollover.
