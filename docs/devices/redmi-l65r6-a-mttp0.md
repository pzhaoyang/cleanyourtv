# Redmi TV L65R6-A (MiTV-MTTP0, hugo), China, Android 9

From @xxb26553663-star's report in #1063: a same-day session on one set, six packages disabled. Not a
several-day list; full-reboot and OTA persistence are not verified yet. This is **not** the
[`redmi-mitv-mftp0`](../../examples/redmi-mitv-mftp0/debloat.txt) (`alita`) set.

| Field | Observed value |
|---|---|
| Retail model | Redmi TV L65R6-A |
| System model / codename | `MiTV-MTTP0` / `hugo` |
| Android / API | Android 9 / 28, China-market firmware |
| Build | `21.4.9.2585` (incremental `2585`), MiTV OS 2.9.4 |
| Stock Home | `com.mitv.tvhome/.MainActivityUserMode` (PatchWall 3.50.1621), left enabled |

## Disabled

One `pm disable-user --user 0` per package, each read back:

```text
com.miui.tv.analytics
com.miui.analytics
com.xiaomi.statistic
com.xm.webcontent
com.mitv.shoplugin
com.xiaomi.mitv.shop
```

Kept: the stock Home, TV and input services, voice and casting, the app store, the firmware updater and the
streaming apps. `com.mitv.care` was already disabled before the session and is not part of this set.

## Home: the allowlist identity

The ordinary `com.gathin.tvhome` build installed and opened, but `set-home-activity` printed `Success` and Home
still resolved to PatchWall: the same package-name HOME allowlist as the MiTV-AETP0 and MiTV-MTEQ0
([`xiaomi-mitv-aetp0-dofus.md`](xiaomi-mitv-aetp0-dofus.md),
[`xiaomi-mitv-mteq0-baymax.md`](xiaomi-mitv-mteq0-baymax.md)). A build from the same source with
`-PlauncherApplicationId=com.ts.launcher` became the resolved Home, with PatchWall still installed and enabled.

- Check `pm path com.ts.launcher` prints nothing before installing under that identity.
- Install it with `STOCK_LAUNCHER=none` (or by hand as in the MTEQ0 note): the default
  `launcher/build-and-install.sh` disables the stock launcher, which this report did not do.

## Inputs and boot ad

- The Inputs menu listed HDMI 1/2/3 and Composite. HDMI 1 opened Xiaomi's external-source activity, and Home
  came back to the replacement launcher.
- The panel reports three HDMI ports but the set has two: `dumpsys tv_input` lists `HDMIInputService/HW5`,
  `/HW6` and `/HW7`, and `dumpsys hdmi_control` shows the CEC device on physical address `0x1000`, port 1.
  `HW7` has no socket, so `input_names` can rename `HW5`/`HW6` and leave `HW7` unnamed — an unnamed hardware
  input keeps the framework's label, and the stock Sources menu numbers the ports one lower than the framework
  does (`HW5` = "HDMI 2", `HW6` = "HDMI 1"), which is what the owner asked the menu to match.
- Both tuners are registered by the one `com.mediatek.tvinput/.tuner.TunerInputService`, as `HW0` and `HW1`,
  with the same label "Tuner": `HW0` is DTMB (what the Live TV row plays), `HW1` is the analog ATV source. They
  are told apart only by the action that opens them — `com.xiaomi.mitv.tvplayer.ATV_PLAY` binds `HW1`,
  `.DTMB_PLAY` binds `HW0` — or by naming one in `input_names` ("HW1=Analog TV"), which is what makes
  `show_analog_tv` list it; nothing in the id or the label says which one is analog.
- The analog tuner plays through the external-source activity: `EXTSRC_PLAY --ei input 1` binds `HW1`, while
  `--ei input 0` binds nothing, so `input_sources` wants `HW1=1` for that row rather than `HW1=0`.
- That row showed a black screen where the stock Sources menu showed the snow of an untuned channel. Binding
  `HW1` is not enough: only `com.xiaomi.mitv.tvplayer.ATV_PLAY` starts `AtvActivity`, which drives the tuner
  (and so draws the snow), while the external-source activity leaves the ATV path unset. `input_actions`
  `HW1=com.xiaomi.mitv.tvplayer.ATV_PLAY` opens the row that way, and the entry stays in `input_sources` so the
  input is still listed and still has a fallback.
- `settings put system boot_ad_switch 0` read back as `0`, and the owner saw no startup ad after turning the TV
  off and on (an owner power cycle, not a verified full reboot). The same key works on other China Xiaomi builds
  ([`xiaomi-l43m7-ea-antl0.md`](xiaomi-l43m7-ea-antl0.md)); undo with `settings delete system boot_ad_switch`.

The owner confirmed the picture, the physical remote, HDMI and normal viewing during staged checks.
