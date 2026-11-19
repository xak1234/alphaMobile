# Messages, tasks and device status — 0.2.0

## Behaviour

The conversation composer allows up to three visible lines and follows Android keyboard insets. The keyboard stays in the same window instead of using a separate full-screen editor. Incoming snapshots preserve an existing draft.

New agent replies add an unread count to Messages on the dashboard and inner navigation. The Agent messages Android notification channel supports sound, vibration and a launcher badge. An alert repeats at most every 30 seconds until the unread conversation is opened. Reading one conversation leaves other conversations unread. Read state survives restarting the app; repeated snapshots cannot revive the same read reply. Historical messages present at upgrade are not all alerted again. A streamed reply that adds content can become unread again.

Become available to keep the receiver working with the app closed. Quiet mode silences alerts; pings also pause during voice calls/ringing. Android notification permissions, channel sound, volume, Do Not Disturb and battery restrictions still apply. Force-stopping the app stops background reception. Opening a conversation marks its received messages read.

Calls, Messages and Tasks start collapsed to one physical line containing the agent, date/time and subject, plus an expand arrow. Long agent names and subjects are ellipsized on the line; expanding shows full metadata and text. Dates in the collapsed row use the phone’s local time (`dd/MM HH:mm`); expanded metadata includes the full date/year. Calls use their start time, chats their latest update, replies their creation time, and tasks their latest finished/received run time or schedule-update time if no result exists.

Tap the row to expand or collapse it. Call actions, conversation Reply and full task-history actions are inside the expanded content. Expansion state survives snapshot refreshes, theme changes and activity recreation, and starts collapsed for a fresh session. Merely listing a message does not mark it read. Expanding a visible saved reply acknowledges that reply; expanding a chat acknowledges only the actual message revisions displayed. The app does not acknowledge newly received inline content while hidden.

Expanded Tasks shows the latest reported run status, summary, output preview and run time. Open a task for the full result and recent run history. Results are ordered by receipt/update time; repeated reports with the same run key update that run. Agents must send real results through the configured MCP connection. The app cannot discover an unreported Claude result by itself.

The slim top strip reads the device's current network and signal, battery percentage and charging state. It refreshes while the app is visible. Low battery (15% or less, unplugged) uses red. Wi-Fi strength uses Android RSSI converted to four bars; cellular uses Android's modem level where available. Missing readings show an unavailable state. This requires only the normal network/Wi-Fi permissions, not location or SIM identifiers. The separate central AI indicator still represents the agent connection.

## Modules

- `DeviceStatus.java`: reads Android network/battery services and draws accessible signal and battery meters.
- `MessageState.java`: persists read status by message ID and content revision; prevents duplicate snapshot alerts.
- `MessageAlerts.java`: notification channel, unread count, quiet-mode handling and 30-second reminders.
- `Data.java`: merges the actual agent feed and updates read state under a shared lock.
- `MainActivity.java`: keyboard insets, unread UI, latest task outputs, clear Settings and device strip.
- `Ui.java`: Midnight blue palette and shaded backgrounds alongside existing themes.
- `backend/alphamobile/app.py` and `store.py`: bounded latest-result previews and task ordering by latest reported update.

## Minimal integration tests

Build with Java 17 and the Android SDK versions specified in `android/app/build.gradle`. Release and test APKs must share the private release certificate for upgrade tests; never put that key in source. Use a disposable emulator identity, never the user's phone identity.

```powershell
adb -s emulator-5558 shell am instrument -w -e operation keyboard -e agent_id TEST_AGENT_ID org.alphamobile.app.test/org.alphamobile.app.SmokeInstrumentation
adb -s emulator-5558 shell am instrument -w -e operation unread org.alphamobile.app.test/org.alphamobile.app.SmokeInstrumentation
```

`keyboard` creates a real chat and opens the Android keyboard. Expected: `PASS: keyboard`, with the whole composer above the keyboard and the draft intact. `unread` requires exactly two new actual relay replies in separate test conversations after the app's initial sync. Expected: `PASS: unread`, including the first alert, its repeat after 30 seconds, separate read counts and no further reminder once both are read. `read_persist` checks read state after restarting the process.

`status_settings` checks displayed Android battery data, generic connection wording, persistent switches and selected blue theme. The optional `battery` argument is the exact expected label. For repeatable battery tests, Android emulator battery commands can set a reading, but reset them immediately afterwards. Production code never inserts a fabricated reading.

`tasks_latest` takes an `output` argument matching the text of a reported test run and opens its task and verifies the output appears inline. `screens` captures actual dark, blue and light screens for visual inspection. `compact_lists` uses isolated actual relay records to check single-line metadata, default collapse, expand/collapse, state preservation on refresh, task output, unread-on-expansion and the Reply action. Expected: `PASS: compact_lists`. See VALIDATION.md for observed release results and physical-device limits.
