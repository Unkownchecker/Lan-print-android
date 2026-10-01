# LAN Print for Android

Turns an Android phone with a USB-OTG cable into a print server for an
HP LaserJet P1005 / P1006 / P1007 / P1008 / P1505 / P1505n — the same role
the Windows desktop app plays, but driving the printer directly over USB
instead of through a PC. Talks to the same relay server and pairing-ID
system as the desktop app and phone web app, so nothing else in your setup
needs to change.

## Before you start: get the printer's firmware file

**This is not optional — printing will not work at all without it.** These
printers have no persistent firmware storage; a firmware blob has to be
uploaded to them fresh every time they're power-cycled or reconnected. This
app has a "Load printer firmware file" button for exactly this, but you
need to supply the file yourself — it's HP's own proprietary firmware, not
something this app (or its GPL-licensed printing code) can legally bundle.

**Important: which file to use depends on your model, and it's not always
the obvious one:**

| Your printer | Firmware file you need |
|---|---|
| P1005 | `sihpP1005.dl` |
| P1006 | `sihpP1006.dl` |
| **P1007** | **`sihpP1005.dl`** (same hardware as the P1005, different USB ID) |
| **P1008** | **`sihpP1006.dl`** (same hardware as the P1006, different USB ID) |
| P1505 | `sihpP1505.dl` |
| P1505n | `sihpP1505n.dl` |

Get it from the `foo2zjs` project's own firmware mirror:
`http://foo2zjs.rkkda.com/firmware/` — download the file matching the table
above (via a browser on your phone, or download on a computer and transfer
it over). Then, in the app, plug in your printer, wait for it to connect,
and tap **"Load printer firmware file"** to pick it.

You'll need to reload it any time the printer loses power (unplugged,
turned off, etc.) — the app tracks this per-connection and will tell you if
firmware hasn't been sent yet.

## Building the APK (via GitHub Actions — no Android Studio needed)

1. Create a new GitHub repository and push this entire folder to it.
2. GitHub Actions will automatically build a debug APK on every push (see
   `.github/workflows/build.yml`) — check the **Actions** tab on your repo,
   open the latest run, and download the `lan-print-debug-apk` artifact
   once it finishes (a few minutes).
3. Unzip that artifact to get `app-debug.apk`.
4. Transfer it to your phone and install it — you'll need to enable
   "Install unknown apps" for whichever app you use to open it (Settings
   will prompt you the first time).

If you'd rather build locally with Android Studio instead, open this folder
as a project — it's a normal Gradle/Android project — and run/build as
usual. `./gradlew assembleDebug` also works directly if you have Android's
SDK + NDK already installed.

## Using it

1. Plug the printer into your phone via a USB-OTG adapter/cable, then open
   the app (or let it prompt you when the printer is plugged in).
2. Grant USB permission when asked.
3. Load the printer's firmware file (see above) — the app will tell you if
   this hasn't been done yet.
4. For same-network printing: nothing else needed — the phone web app's
   local-network mode should be able to reach... actually, this app is
   itself the print server, not a phone browsing to one — see "How the
   phone web app connects to this" below.
5. For printing from anywhere: paste your relay's URL into "Print from
   anywhere" and note the pairing ID, exactly like the desktop app.

### How the phone web app connects to this

This app doesn't run a local HTTP server the way the Windows app does (a
phone can't easily browse to "another phone's" address the same way it
browses to a PC on the same Wi-Fi). Realistically, this app is most useful
in **"Anywhere" (relay) mode**: any device with the phone web app — a
different phone, a laptop, whatever — enters your relay URL and this app's
pairing ID, exactly as if it were pairing with the Windows desktop app. If
you want the phone running this print-server app to also be reachable by
name on the local network the way the PC is, that would need a local HTTP
server added to this app too — not included here, since the relay path
already covers the "print from any device" need.

## What this does and doesn't support yet

- **PDF files only.** Non-PDF files (images, Word docs, etc.) get a clear
  error rather than silently failing — convert to PDF first, same
  recommendation as the desktop app gives for its own fallback path.
- **One printer at a time** — whichever one is currently plugged in and
  connected.
- **No hardware duplex.** These printers don't have one over USB. Manual
  duplex (print odd pages, flip the stack, print even pages) works exactly
  like the desktop app's version, including over the relay.
- Runs as a foreground Service with a persistent notification so Android
  doesn't kill it in the background — you'll also be prompted to exempt it
  from battery optimization, which matters if you want it reliably
  reachable from "anywhere" while you're not actively looking at the phone.

## Licensing — read this before distributing the APK to anyone else

The actual printer-language conversion (`foo2xqx.c`, the JBIG compression
code) is taken from the **foo2zjs** project and is licensed
**GPL-2.0-or-later** (see `LICENSE` in this repo — extracted from foo2zjs's
own `COPYING` file). Because that code is compiled directly into this app's
native library rather than run as a separate process, the resulting APK is
a combined/derivative work, and **the GPL's terms most likely extend to the
whole app**, not just the C portion.

Practically, this means:
- **Using it yourself** (sideloading, personal use): no issue at all.
- **Giving the APK to someone else, or publishing it anywhere** (a repo
  release, an app store, a forum): you should make this project's complete
  source available to whoever receives it (which this repository already
  does, if you keep it public) and keep the GPL notice intact. Don't strip
  the license or distribute a closed/obfuscated build.

None of HP's proprietary firmware is included in this repository — only
the open-source driver code that talks to the printer once it's already
running that firmware.
