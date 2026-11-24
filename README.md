# GShock Smart Ass

**Arthur Dent's unofficial fork of [G-Shock Smart Sync](https://github.com/izivkov/CasioGShockSmartSync), built specifically for the Casio G-SHOCK DW-H5600.**

The upstream app talks to dozens of Casio watches. This fork talks to one, and goes much further with it: it treats the DW-H5600 as an always-connected companion and moves into the phone everything the watch cannot do on its own — unlimited alarms, per-alarm sound and days, a snooze the phone can stop, a watch face that follows the sunset, the weather on the sun screen, and step history.

It is not affiliated with Casio or with the upstream project. It has only been tested with one DW-H5600 (module 3516) on one Pixel; other watches will connect but get none of the extras.

<p align="center">
  <img src="docs/screenshots/01_cover.png" width="220" alt="Cover screen: Ride to unlock">
  <img src="docs/screenshots/03_time.png" width="220" alt="Time tab">
  <img src="docs/screenshots/05_alarms.png" width="220" alt="Alarms tab">
</p>

---

## What it does

### Alarms — as many as you like

<img src="docs/screenshots/05_alarms.png" width="240" align="right" alt="Alarms tab">

The DW-H5600 has four alarm slots and one smart-alarm slot. This app keeps an unlimited list on the phone and loads the alarms due in the next 24 hours into the watch, swapping them as they ring (cards marked *on watch*).

Each alarm has:

- a **label** (phone only — the watch has no alarm names)
- **repeat**: *Once*, every day, Mon–Fri, weekends or any set of days
- **Sound + vibe** or **Vibe only** — the watch has one sound mode for everything, so the alarm *borrows* it: five minutes before it rings the mode it needs is set, and after the ring (or once its snooze is stopped) your own mode comes back
- **snooze**: off by default; switched on, every 5/10/15/20/30 minutes (slider) until you stop it
- **smart wake** (off by default; slider): ring up to 15/30/45/60 minutes early, in light sleep (needs heart-rate monitoring *All day* or *Activities + night*, Sport tab)

A **one-time** alarm switches itself off after it has rung (and its snooze has been stopped), and is set again for its next time when you switch it back on.

Hold an alarm and drag it to reorder the list; new alarms go to the top. Edits show instantly on the phone and reach the watch in the background (merged and queued, retried if the watch does not answer).

<br clear="right">

<img src="docs/screenshots/06_alarm_editor.png" width="240" align="left" alt="Alarm editor">

**One editor for everything.** Tap *+ Add alarm* or any alarm card; all options are set before anything is sent, so the watch gets a single update.

**A snooze that keeps going until you stop it.** The watch's own snooze is never used: it gives up after 7 repeats, cannot be stopped from the watch without switching the alarm off, and any time sync cancels it. Instead the phone snoozes: after an alarm with snooze rings, the phone moves its watch slot to the next repeat (ring + 5 min, + 10 min, … at the interval you picked) a minute after each ring, with no limit. A *8:10 · next 8:15 — Stop snooze* notification (and a button on the card) ends it; a button press on the watch only silences one ring. A smart alarm snoozes from its set time, and its snooze can already be stopped inside its window when it woke you early.

<br clear="left">

### Custom — the DW-H5600's own features

<p align="center">
  <img src="docs/screenshots/07_custom_1.png" width="220" alt="Custom tab: watch face and sun">
  <img src="docs/screenshots/08_custom_2.png" width="220" alt="Custom tab: sound and notifications">
  <img src="docs/screenshots/09_custom_3.png" width="220" alt="Custom tab: Find Phone, greeting and ride">
</p>

- **Watch face** — Face 1 (weekday) or Face 2 with its item: **Auto** (steps by day, the sun screen before sunset; see below), Sun, Steps, Moon, Heart rate, Sleep. Picking Heart rate offers to switch heart-rate monitoring to *All day* (the item shows nothing without it); leaving it offers to switch back.
- **Sun & weather** (shown with the Sun or Auto item) — *Sun at home* and *Weather instead of sunrise* (both below). One line shows what the watch should be showing; tap it for today's real sunrise and sunset, the current weather (the refresh icon fetches it and sends it to the watch at once) and the fine-tune buttons.
- **Sun at home** (Auto only) — while the phone is on one of your home Wi-Fi networks, Auto shows the sun screen all day: no point counting steps from room to room. Add any number of networks (2.4 GHz, 5 GHz, an extender) from a list of the networks in range; away from them, Auto works as below. It switches the moment the Wi-Fi connects or drops. Android reveals Wi-Fi names only with precise location allowed *all the time*, so the app asks for that.
- **Sound mode** — Sound + vibe, Sound only, Vibe only, Off, Custom. This is *your* mode: alarms borrow the watch briefly and give it back.
- **Silent at night** — no sound and no vibration from the start to the end of your night time; alarms still ring.
- **Notifications only from chosen apps** — a per-app allowlist for what reaches the watch.
- **Find Phone** — pick the ring sound and volume, vibrate too, test it. Pressing Find Phone on the watch rings the phone at once (the watch stays connected, so there is no reconnect wait); pressing *back* on the watch stops it.
- **Start screen greeting** and **What are we riding?** — see *The fun bits* below.

#### Weather instead of sunrise

The DW-H5600 has no weather, but it has a sun screen: sunrise on top, sunset below, both calculated by the watch from the home location it is given. This feature gives it a location chosen so that the "sunrise" reads as the current weather, while the sunset stays the real one.

The top line reads **`W:TT`**:

| `W` | weather |
|---|---|
| 1 | sunny — clear, or clouds up to 30 % |
| 2 | cloudy — overcast, fog |
| 3 | rain or snow — any precipitation |
| 4 | extreme — heavy rain or snow, thunderstorm, more than 2 mm in 15 minutes, wind from 40 km/h or gusts from 60 km/h |

`TT` is the outdoor temperature in °C. So `1:18` is sunny and 18 °C, `3:07` is rain and 7 °C.

- The current conditions come from [Open-Meteo](https://open-meteo.com) for the phone's location. They are fetched at one minute past every hour (not from 00:00 to 06:00) and sent to the watch while it is connected. Every sync wakes the watch's radio, so the app keeps them hourly to save its battery.
- How it works: the app solves for a latitude and longitude where today's sunrise falls at `W:TT` local time and the sunset falls at the real sunset where you are. It uses the same horizon angle the watch's own formula appears to use, measured against the watch, so the display lands on the right minute. If your watch is still a minute off, use the *Top line* and *Sunset* fine-tune buttons.
- The watch only recalculates its sun times when its time is set, so each update is a location write plus a time sync. Updates skip the minute an alarm rings, so a time sync never cuts a ring short.
- Limits: temperatures below 0 °C show as `00`, and above 59 °C as `59`. The real sunrise is no longer shown (the *Real today* card in the app still shows it). With no connection or no internet, the watch keeps the last weather it received.

#### Auto face (steps / sun)

Face 2 shows one item at a time. With **Auto** chosen as the item, the phone picks it for you:

- **Steps** for most of the day;
- the **sun screen** from **four hours before sunset until ten minutes after** (the lead time is adjustable in 15-minute steps, from 15 minutes to 12 hours) — with *Weather instead of sunrise* on, that is the weather and temperature on top and today's sunset below.

The app works out today's sunset from the phone's location and switches the face exactly at both edges with an exact Android alarm, so it also happens when the phone is asleep. Picking any other item switches Auto off. During polar day or night (no sunset), the face stays on Steps.

Every card has an (i) button with its explanation; the cards themselves show only values.

### Time

<img src="docs/screenshots/03_time.png" width="240" align="right" alt="Time tab">

- **Fine Adjustment** — the offset applied whenever the watch's time is set.
- **Extra clocks**, as many as you like, each with its date. The small **+** adds one; tap a clock to rename it, change its time zone (searchable list of cities) or delete it; hold and drag to reorder. Switch on **World time on the watch** for one of them and it becomes the watch's world-time city (name, time zone and DST), sent with every time sync.
- **Timer**.
- **Watch time format** 12h / 24h — the whole app follows the watch.
- **Hourly time signal**.
- **Home time** and the **watch sensor** temperature (it reads body heat, not the weather).
- **Battery** as the watch shows it: four pastel bars (red, orange, light green, green). The watch reports a level, not a percentage; tap the battery to tell the app how many bars the watch shows for the current level (until then the bars are drawn lighter).

<br clear="right">

### Health

<img src="docs/screenshots/04_health.png" width="240" align="left" alt="Health tab">

Steps from the watch's LifeLog, on one screen: **today** (with the goal set on the watch, distance and calories), **hourly** (24 hours, scroll sideways, opens at the current hour), **daily** (the last seven days) and **monthly** (six months).

The watch only ever sends steps the phone has not fetched yet, and cannot send them again. So the app keeps every sync on the phone (5-minute slots and daily totals, merged so nothing counts twice) and draws the charts from that history. Hours and days it has no data for stay empty ("–") rather than showing 0.

A transfer takes a while — the watch sends in small pieces — so the last result shows at once with *Synced at …* while a new sync runs. Pull down to sync now.

<br clear="left">

### Sport

<img src="docs/screenshots/12_sport.png" width="240" align="right" alt="Sport tab">

- **Heart-rate monitor**: *All day* (daytime heart rate, calories, sleep evaluation), *Activities + night*, or *Off* — the watch's battery trade-off. Smart alarms need one of the first two.
- **Heart-rate values**: max and resting heart rate, the watch's own estimate or your own value; VO2max as measured by the watch.
- **Daily step goal** (− / + in 500s), a **notification** when you reach it, and the **step reminder** (the watch nudges you after a long time without moving).

<br clear="right">

### Settings

<img src="docs/screenshots/10_settings.png" width="240" align="right" alt="Settings tab">

Button tone and vibration, auto light, illumination period, power saving — each change is written to the watch straight away (no *Send* button), on top of a fresh read so nothing else on the watch is touched.

- **Night time** — your sleep time on the watch (sleep tracking, night-time heart rate, and the window for *Silent at night*).
- **Watch modes** — the order of the watch's modes (hold and drag) and which ones it shows (switch off the ones you never use), saved to the watch in one go. Collapsed until you tap it.

**Disconnect** drops the connection after a confirmation; the watch reconnects the usual way (its connect button).

<br clear="right">

### The fun bits

<p align="center">
  <img src="docs/screenshots/02_start_screen.png" width="220" alt="Start screen with the greeting ticker">
  <img src="docs/screenshots/01_cover.png" width="220" alt="Cover screen: Ride to unlock">
  <img src="docs/screenshots/09_custom_3.png" width="220" alt="Greeting and ride settings at the bottom of the Custom tab">
</p>

- **Start screen greeting** — shown while the app waits for the watch, optionally as a stepping LED ticker. Tap *Connected* on the cover to see it. Change the text and switch the ticker on at the bottom of the Custom tab.
- **Ride to unlock.** The app opens on a cover screen: drag the ride sideways to get in. It accelerates past your finger; pulled left it spins (faster the further you pull) and, released, launches off the right edge before the app opens.
- **What are we riding?** — any text up to 14 characters, emoji welcome. 🚲 by default. Set it at the bottom of the Custom tab.
- **Pull up to lock** — on any tab already at the bottom, keep pulling up: a lock appears above the tab bar, release to lock. Or use the *Lock* button.
- The app reopens on the tab you used last, and stays upright (portrait only).

---

## Under the hood

The watch stays connected all day (the app repeats the official app's `0x39` handshake), and a scheduler on the phone does the watch's planning:

- a minute after an alarm or a snooze repeat rings: reload the alarm slots (a snoozing alarm's slot moves to its next repeat), the smart slot and the sound mode, switch finished one-time alarms off
- at the edges of the sunset window: switch the watch face
- five minutes before an alarm and after it (or when its snooze is stopped): switch the sound mode to what the alarm needs and back to yours
- at the start and end of the night: silence the watch, when *Silent at night* is on
- hourly, except from 00:00 to 06:00: refresh the sun data or the weather (a location and time sync — the watch only recalculates its sun times on a time write)
- every 10 minutes, day and night, and 3 minutes after every full time sync: remind the watch of its location with just a location and a time packet. About 1-2 minutes after a full sync (the one with the city packets) the watch resets its sun location by itself and shows dashes; there is no way to read whether it still has it. Weather updates and the Sun & weather buttons send only these two packets.
- on every connection: one location and time sync

It wakes itself with exact Android alarms, so this keeps working while the phone dozes overnight. Requests to the watch are shared between screens, retried when the watch does not answer and serialized per setting, so two quick changes cannot undo each other.

The protocol details found along the way (sound mode `0x42`, watch face `0x51`, smart alarm `0x50`, heart-rate monitor `0x41`, step goal `0x43`, sleep and heart-rate values `0x4F`, watch modes `0x52`, home location `0x24`, LifeLog, …) are written up in the [GShockAPI fork](https://github.com/flyboy14/GShockAPI/tree/h5600-sound-mode) in `docs/DW-H5600-protocol.md`. Many of them were read out of the official app's own code and then tested on the watch — it acknowledges plenty of settings it does not keep, so only what reads back changed made it into the app.

More for this app:

- [`docs/DW-H5600.md`](docs/DW-H5600.md) — what each tab does, and where the code is
- [`docs/DW-H5600-DEVELOPMENT.md`](docs/DW-H5600-DEVELOPMENT.md) — how to add the next feature: the patterns used everywhere, testing on the watch, reading the official app
- [`docs/DW-H5600-feature-map.md`](docs/DW-H5600-feature-map.md) — every setting the official app knows, and what this watch actually keeps
- `tools/` — small helpers for testing over adb

## Building and installing

There is no release build; build it yourself. The app compiles against the GShockAPI fork from source, so clone both side by side:

```
git clone -b h5600-sound-mode https://github.com/flyboy14/GShockAPI.git
git clone -b h5600-sound-mode https://github.com/flyboy14/GShockSmartass.git
cd GShockSmartass
./gradlew testGithubDebugUnitTest assembleGithubDebug     # JDK 21, Android SDK 37
adb install -r app/build/outputs/apk/github/debug/app-github-debug.apk
```

Android 8 or later. **Switching from the official CASIO WATCHES app:** unpair on the watch (SETTING → GENERAL SETTINGS → PAIRING → UNPAIRING), forget the watch in Android's Bluetooth settings, then pair it in this app. Only one app can own the watch at a time.

## Known limits

- The watch's alarm slots ring every day; the phone is what switches day-specific and one-time alarms on and off. If the watch is away from the phone for more than a day, they ring on the wrong days until it reconnects.
- The snooze needs the phone: each repeat is written to the watch a minute after the previous ring. If the watch disconnects while an alarm is snoozing, it rings once more at the last repeat time written (and at that time daily) until it reconnects.
- Battery bars beyond the levels you have calibrated are estimates.
- Stopwatch laps cannot be read: the watch sends nothing about its stopwatch.
- Step history starts with the first sync of this version; earlier days cannot be fetched again.
- Not settable on this watch (it ignores them): date order, weekday language, font, distance units, auto pause, heart-rate zone alert, the exercise profile.

## Credits and license

Built on [G-Shock Smart Sync](https://github.com/izivkov/CasioGShockSmartSync) and [GShockAPI](https://github.com/izivkov/GShockAPI) by Ivo Zivkov — all the hard work of talking to Casio watches is theirs. Weather data by [Open-Meteo](https://open-meteo.com).

Apache License 2.0, like the upstream project — see [LICENSE](LICENSE).
