# Skip Lock

In YouTube Kids, every video picked by tapping a video tile or "Next video" must play for
3 minutes, or to its end if it's shorter, before another can be picked. Paused time counts toward
the 3 minutes. Autoplay involves no tap, so it never starts the lock.

During the lock, on the video screen the suggestions, "Next video", the top bar and the progress
bar are blocked, so only the player's own play/pause works. Every other YouTube Kids
screen is fully covered, so backing out to the home screen doesn't help. A countdown badge shows
at the top for 3 seconds whenever a blocked area is tapped.

## 1. Build and install

Open this folder in Android Studio, let it sync, then Run.

## 2. Enable on the tablet

Open "Skip Lock" → Open accessibility settings → Installed apps → Skip Lock → On.

On Android 13+, if the toggle is greyed out: Settings → Apps → Skip Lock → ⋮ → "Allow restricted settings".

## 3. Test

    adb logcat -s SkipLock

Every tap in YouTube Kids logs `Click id=... desc='...' (picksVideo=true|false)`. The lock length is
`LOCK_MS` in `SkipLockService.kt`.

Detection relies on YouTube Kids' view IDs (`next`, `pause`, `watch_player`) and on video tiles'
descriptions ending in an English duration ("... 14 minutes, 16 seconds"). A YouTube Kids update
or a different device language may need these adjusted.

## Keeping it in place

Use Family Link (or a PIN-protected profile) so the accessibility service can't be switched off
in Settings, and App pinning if the tablet should stay in YouTube Kids.
