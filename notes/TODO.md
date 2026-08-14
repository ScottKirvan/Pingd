TODO
----
- [ ] Design a real app launcher icon. Currently just Stage 0's
      placeholder (a plain white square, `ic_launcher_foreground.xml`) —
      this is what shows as the notification shade's avatar, and was
      mistaken for a tracer-icon bug during emulator testing before
      being traced back to the untouched placeholder.
- [ ] Move the app off `MaterialTheme.colorScheme` (Android's Material-You
      dynamic color) entirely, in favor of a fixed, hand-picked palette.
      First concrete case: the ping-success history graph's gradient
      sweep was originally drawn from `colorScheme.primary`/`secondary`/
      `tertiary`, and on-device this rendered as a single flat,
      barely-tinted line — the dynamic scheme derives all three from the
      wallpaper and they landed too close in hue to produce a visible
      gradient at all (fixed in the history-graph-colors work; see
      `HistoryGraphs.kt`'s `PING_SUCCESS_SWEEP_START` for the fix and
      reasoning). Decision going forward: don't let Android's stock
      dynamic theming constrain this app's design — it hides actionable
      information behind low/no-visible-contrast choices the OS makes on
      its own, which is the opposite of what a glanceable status app
      needs. Still theme-derived today: the gap-shading tint and marker
      lines in `HistoryGraphs.kt` (`onSurfaceVariant`/`outline`), and
      whatever else in `SettingsScreen.kt` hasn't been audited yet.

In Progress
-----------
- [ ] .

Done ✓
------
- [X] .

Not Gonna Do ✓
------
- [X] .