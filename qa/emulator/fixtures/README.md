# BeCalm Emulator QA Fixtures

This folder is for repeatable Android emulator QA.

- `accounts/connected_sources.json`: fake account/source state seeded by the debug broadcast receiver.
- `message_screenshots/`: images pushed to emulator media storage so the Android Photo Picker can select them.

Run:

```bash
qa/emulator/scripts/seed_emulator_qa.sh
```

The script pushes images to `/sdcard/Pictures/BeCalmQa/message_screenshots`, asks Android to scan them into MediaStore, broadcasts the debug seed receiver, and launches the app on the People tab.

For a lightweight ADB smoke test without Espresso/instrumentation:

```bash
qa/emulator/scripts/verify_person_rendering_qa.sh
```

That script re-seeds the emulator, checks the Korean People tab, verifies the `+` evidence import sheet, confirms that the message screenshot path opens Android Photo Picker, and opens a person detail timeline.
