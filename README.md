# VoiceDJ for Android

Always-listening voice control for Spotify that searches **your own library
first** — which is exactly where Google Assistant falls over. "Hey DJ, play my
deep house playlist" lands on `🔥 Deep House Vibes` because the app reads your
actual playlist list and matches against it, after stripping emoji,
punctuation and filler words.

Wake-word detection runs entirely on the phone. No account, no API key, no
subscription, nothing transmitted while it waits.

**Requires Spotify Premium.** Spotify's playback API returns 403 for free
accounts and there is no way around that.

---

## How it listens

Two stages, and only the second one can leave the device.

1. **Wake phrase — on-device, always running.** A small Vosk speech model runs
   locally with its vocabulary restricted to your wake phrase. Nothing is
   written to disk and nothing is transmitted.
2. **The command — a few seconds only.** Once the phrase is heard, the mic is
   handed to Android's speech recogniser to transcribe what you actually said.
   Depending on your phone's settings that happens on-device or at Google,
   exactly as it does for any other voice input.

Say it either way:

- **"Hey DJ"** → it ducks the music → **"play my shed work playlist"**
- **"Hey DJ, play my shed work playlist"** — one breath, no pause

### What Android will and won't allow

Listening **cannot start on its own**. Android forbids starting a microphone
foreground service from the background or at boot, so someone has to open the
app and tap **Start listening** — after a reboot, too. That is a platform rule,
not a limitation of this app, and it is the main reason an always-on mic can't
be made invisible.

While listening is on there is a permanent notification with a **Stop** button
that cannot be swiped away.

### Consent

The first screen is a disclosure that has to be read and explicitly accepted,
with two separate tick boxes, before always-listening can be switched on at
all. Declining is a real choice: the app still works, it just never opens the
mic on its own — tap-to-talk and the on-screen controls carry on.

That decision is stored **per install**, so if you pass the APK to anyone else
they make their own choice on their own phone; it never travels with the file.
It can be withdrawn at any time in Settings, which stops listening
immediately. If the disclosure text ever changes materially, `CONSENT_VERSION`
is bumped and everyone is asked again rather than inheriting an old yes.

---

## Building it

No Android tooling on your own machine required — GitHub's servers do the
build. The one-time repo setup is much easier on a computer; everything after
that works from the phone.

### 1. Put this project in a GitHub repo

On a computer, from the project folder:

```bash
git init
git add .
git commit -m "VoiceDJ"
git branch -M main
git remote add origin https://github.com/<you>/voicedj.git
git push -u origin main
```

(A private repo is fine — Actions works the same.)

### 2. Let it build

The push triggers the build. Watch it under the **Actions** tab. First run
takes about 5 minutes; later ones are quicker thanks to caching.

When it finishes it publishes a **Release** containing `VoiceDJ-<n>.apk`. Use
the release rather than the Actions artifact — a release downloads directly in
the phone browser, an artifact arrives as a zip you then have to unpack.

To rebuild later without pushing anything: **Actions → Build APK → Run
workflow**. That button works fine from a phone.

### 3. Install it

Open the release on the phone, tap the `.apk`, and allow "install unknown
apps" for your browser when prompted. It's a debug-signed build, so Android
will warn you — expected for anything not coming from the Play Store.

### Building locally instead

Open the project in Android Studio and hit Run. One extra step first, because
the speech model isn't committed:

```bash
curl -fSL -o model.zip https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
unzip -q model.zip
mv vosk-model-small-en-us-0.15/* app/src/main/assets/model-en-us/
date +%s > app/src/main/assets/model-en-us/uuid
rm -rf model.zip vosk-model-small-en-us-0.15
```

---

## Connecting Spotify

1. Go to **developer.spotify.com/dashboard** → **Create app**. Any name.
2. Tick **Web API**.
3. Add this exact Redirect URI:

   ```
   au.com.naeco.voicedj://callback
   ```

4. Save, copy the **Client ID**, paste it into the app, tap **Connect
   Spotify**.

No client secret. The app uses Authorization Code with PKCE, which is what
Spotify expects from a native app — there is nowhere safe to keep a secret in
something you hand around.

---

## What you can say

Your library always wins over a similarly-named public playlist.

```
play my deep house playlist        shuffle my shed work playlist
play sunday morning coffee         play thunderstruck by acdc
play album rumours                 play my drive playlist on the kitchen speaker
next / skip                        back / previous
pause / stop                       resume
shuffle / shuffle off              repeat / repeat off
volume 40 / louder / turn it down  what's playing
```

"on Spotify", "please", "can you", "my", "the" are all stripped — say it
however it comes out.

---

## Choosing a wake phrase

Settings → **Change wake phrase**. It's free text; no training, no model to
rebuild.

Two or three distinct syllables work best. Avoid anything that turns up in
normal conversation, or the mic will hand your chat to the recogniser all day.
"Hey DJ", "music boss", "hey maestro" are all fine. Single short words like
"go" are not.

---

## Battery

The wake-word model is deliberately grammar-constrained — it only ever listens
for your phrase, never transcribes everything — which is what makes continuous
listening affordable. Expect a few percent an hour with the screen off. If the
phone is idle for long stretches, turn listening off from the notification.

Some manufacturers (Samsung, Xiaomi, OnePlus, Oppo) kill background services
aggressively. If listening stops on its own, exempt VoiceDJ from battery
optimisation in Android settings.

---

## Troubleshooting

**"No active Spotify device"** — Spotify can only control a device already
visible to Spotify Connect. Open Spotify on the target device and press play
once.

**Wake phrase never triggers** — try a longer phrase. Check the notification
is present, which means the service is actually running.

**It triggers constantly** — your phrase is too close to ordinary speech.
Change it to something more distinctive.

**"INVALID_CLIENT: Invalid redirect URI"** — the dashboard entry doesn't match
`au.com.naeco.voicedj://callback` exactly.

**Build fails on the model download step** — alphacephei.com was unreachable.
Re-run the workflow.

---

## Licences

Vosk (`com.alphacephei:vosk-android`) is Apache-2.0, and the
`vosk-model-small-en-us-0.15` model is Apache-2.0. Both are free for personal
and commercial use with no key or account.

*Porcupine was the obvious pick for this job until Picovoice withdrew its free
tier on 30 June 2026; it now needs an enterprise plan. Vosk replaces it, and
as a side benefit gives you an arbitrary wake phrase instead of a fixed list.*
