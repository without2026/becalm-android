# BeCalm Emulator QA Fixtures

This folder is for repeatable Android emulator QA.

- `accounts/connected_sources.json`: fake account/source state seeded by the debug broadcast receiver.
- `message_screenshots/`: images pushed to emulator media storage so the Android Photo Picker can select them.

Run:

```bash
qa/emulator/scripts/seed_emulator_qa.sh
```

The script pushes images to `/sdcard/Pictures/BeCalmQa/message_screenshots`, asks Android to scan them into MediaStore, broadcasts the debug seed receiver, and launches the app on the People tab.
It clears `com.becalm.android` app data first by default so smoke tests are repeatable. Set `BECALM_QA_RESET_APP_DATA=0` only when intentionally inspecting an existing emulator state.

For a lightweight ADB smoke test without Espresso/instrumentation:

```bash
qa/emulator/scripts/verify_person_rendering_qa.sh
```

That script re-seeds the emulator, checks the Korean People tab, verifies the `+` evidence import sheet, confirms that the message screenshot path opens Android Photo Picker, and opens a person detail timeline.

For the meeting speaker review journey (`E2E-073`):

```bash
qa/emulator/scripts/verify_meeting_speaker_matching_qa.sh
```

That script re-seeds the emulator, pushes a meeting audio fixture, verifies the persistent matching banner, selects an existing person for a speaker-label review item, opens the matched person detail timeline, and checks that local `memory.md` output exists for the matched person.

For a single beta-readiness smoke that includes UI and non-functional measurements:

```bash
qa/emulator/scripts/verify_beta_readiness_qa.sh
```

That script runs the person rendering smoke, the meeting speaker matching smoke, then writes cold-start, memory, and frame-stat measurements under `qa/emulator/reports/readiness/`.

For a real Android instrumentation smoke against a connected emulator/device:

```bash
ADB=/mnt/c/Users/jakek/AppData/Local/Android/Sdk/platform-tools/adb.exe \
ANDROID_SERIAL=emulator-5554 \
qa/emulator/scripts/run_source_instrumentation_smoke.sh
```

That script builds `assembleDebug` + `assembleDebugAndroidTest`, installs both APKs, and runs the source list/detail instrumentation subset with `am instrument`. Override `TEST_CLASSES` to run a different comma-separated class list.
