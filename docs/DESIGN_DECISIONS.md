# Design gaps filled

The build spec (`MENTAT_BUILD_SPEC.md`) left these points open or ambiguous. Each decision below is the
smallest choice that keeps the spec's rules intact. Nothing in Sections 1, 2 or 4 was changed, except the
StrictMode refinement (gap 1), which is flagged for your review.

## Engineering

1. **StrictMode `penaltyDeath` (Rule 1).** The deliverable is a *debug* APK you will use daily. A blanket
   `penaltyDeath` would also kill the app for disk reads done inside OxygenOS framework code. Instead, on
   API 28+ a violation crashes the app only when its stack passes through the app's own I/O layers
   (`data`, `system`, `vm`, `domain`); platform-only violations are logged. API 26-27 log only.
   *Please confirm this is acceptable; switching to plain `penaltyDeath()` is a one-line change in `StrictModeSetup`.*
2. **Typed Groq key and Rule 3.** Typed-but-unsaved input normally lives in `SavedStateHandle`, but saved state
   can be written to disk. The key being typed lives only in ViewModel memory (survives rotation, not process
   death). Rule 5 wins over Rule 3 here.
3. **`configChanges`.** Read as "do not declare it as a workaround": the manifest does not declare it; rotation
   recreates the Activity and ViewModels keep state.
4. **AiError additions.** `NO_KEY`, `PAUSED` (circuit breaker open) and `BUSY` (another call in flight) were added
   so the UI can explain each guard.
5. **`max_tokens`.** Reasoning models count thinking tokens, so `max_completion_tokens` is 6000 for the Skill
   Architect, 1500-3000 for the others (spec said about 2500).
6. **Repair prompt** is sent as: original system prompt, the previous output as an assistant message, then the
   Section 10.8 repair text. No other context.
7. **Prompt-injection guard.** User text is stripped of `<user_input>` / `</user_input>` before it is wrapped.
8. **Retry and breaker.** TIMEOUT/SERVER retry once after 2 s; 429 never retried. Any failed call counts toward
   the breaker (3 in a row pauses AI for 5 minutes).
9. **Stable signing locally.** Without CI secrets, builds use the build machine's standard debug key, so APKs from
   the same PC update each other. CI uses the keystore secrets from Section 15.2 when present.

## Data model

10. `Activity` gained `miniSkillId`, `order`, `completed`, `preferTiny`, `weekendOnly`, `steps`; `QuestLog` gained
    `skillId`, `title`, `estMinutes`, `loggedAt` (needed for calibration and the overrun signal).
11. **Sampler session** is stored as a special mini-skill (`isSampler`, order 0) with one activity, so logging,
    tiny versions and proofs work the same way. It never counts toward levels or recall.
12. **Capstone** is a planner candidate once every mini-skill is complete. Logging a capstone session has no
    activity row (`activityId = null`); a checkbox marks the capstone finished.
13. Styles, resources and assumptions are stored as JSON columns on the skill.
14. Sleep window, exam mode dates, budget, session length and freeze days live on the profile row.
15. Proof photos are stored as file names under the app's private `files/proofs` (portable for backup/restore),
    downscaled to max 1600 px JPEG.

## Planner (6.1)

16. Score terms: `priority_norm = priority/5`; `deadline_urgency = 1 - daysLeft/60` (clamped) from a linked
    goal's target date; `recall_overdue = min(1, (daysOverdue+1)/7)`; `unlocks_next_step = 1` when the activity is
    the last unfinished one in its mini-skill; `goal_ladder_core = 1` for skills linked as core; `recent_repetition = 1`
    when the skill was logged today or yesterday; `energy_fit = 1/0` depending on whether a peak-bucket window has
    room (0.5 with fewer than 14 energy logs).
17. When planning for today, nothing is placed before "now" (rounded up to 5 minutes). A 5-minute gap is left
    between blocks inside a window.
18. **Tiny guarantee.** If no tiny block was placed, the tiny version of the best unplaced candidate (or the top
    one) is added as a "Minimum day" block, timed if any window has room, otherwise "Anytime".
19. **Weekly wildcard** = the sampler of the oldest SAMPLING skill, once per Monday-Sunday week.
20. Recall checks of paused or dropped skills are not planned.
21. "Next activity" respects prerequisites; a mini-skill with no selected activities never blocks the path.
    Weekend-only activities are skipped on weekdays.

## Weakness engine (6.2)

22. Evidence for a signal only counts after that signal last fired for the same target, so it can fire again
    exactly at threshold. A question left unanswered past its day counts as ignored (7-day snooze).
23. "Rescheduled" is not a concept in the app, so the 3-times signal counts skips.
24. **too_hard**: split into "part 1"/"part 2" with halved estimates; "move one step back" = a review recall
    check, due today, for the previous (prerequisite) mini-skill if it was completed.
25. **boring**: up to 3 other unfinished activities of the same skill; the pick is pinned as next.
26. **no_time**: the user chooses "always tiny" or "weekends only" for that activity.
27. **other**: free text is stored and the skill page opens (the manual action menu).

## Recall (6.4)

28. +7 and +30 are created on completion; +90 is created when the +30 check is answered and +180 when the +90
    check is answered (offsets from the completion date). The recall question shown rotates by sequence number.
    Results are self-reported; teach-back text is stored locally and never graded.

## Levels, consistency

29. Levels are computed per skill and overall (sum of all skills; idle days = days since the last log).
    Claimed onboarding levels are shown as "unverified" and only marked verified after a recall YES in a skill
    with a matching name. They never add points.
30. Weekly consistency = logged days (tiny counts, recall answers count) in the Mon-Sun week, target `7 - freeze`
    with 1 freeze day by default (editable 0-3).

## AI agents and gates

31. The Command Parser receives every non-dropped skill (paused ones are marked) so `resume_skill` can work.
32. **G4**: answering the clarifying question re-runs A5 once as a new explicit action and supersedes the old
    proposal; low-confidence commands can also be fixed by editing their values.
33. **G6**: remaining budget = monthly budget minus costs of skills created this calendar month. "Free path"
    records the skill cost as 0.
34. A1 (onboarding interviewer) is replaced by a plain form, and A7 + the diet module (Phase 8) are not built,
    as Section 16 scope control allows. Their prompts are still stored in `Prompts.kt`.
35. **Offline queue**: an AI action attempted offline (or without network) is saved as a pending action and only
    runs when you tap "Run now" (Inbox > Pending AI). Transient failures keep it queued.

## Features

36. **Hand-seeded skills** (open decision 1): Sketching basics, Probability basics and SQL basics, written by hand
    and checked by a unit test against the full AI validator. Replace them in `app/src/main/assets/seed_skills.json`.
37. **Combos**: 14 hand-seeded rows in `assets/combos.json`; requirements match skill names by substring.
    Accepting adds a project idea to the inbox.
38. **Curiosity "sample"** creates a SAMPLING skill that only has a sampler session (no AI), which then becomes
    the weekly wildcard. "Promote" opens skill creation pre-filled.
39. **`.ics` import**: hand-written parser (VEVENT, DTSTART/DTEND/DURATION, TZID/UTC/floating times, RRULE
    DAILY/WEEKLY with INTERVAL/COUNT/UNTIL/BYDAY, EXDATE). All-day events are skipped; MONTHLY/YEARLY rules import
    only their first occurrence. Re-importing replaces the previous import; events titled "exam" become EXAM blocks.
40. **Notifications**: reminder times default to 07:30 plan, 18:00 recall, 20:30 nudge, Sunday 19:00 review,
    wind-down 30 minutes before sleep (off). The weekly review includes the export reminder when the last export
    is over 7 days old. A notification tap for the weekly review opens the Inbox.
41. **Export** writes `data.json` + `proofs/` into one zip via the system file picker; the Groq key is never
    exported. **Import** replaces all data after an explicit confirmation (G3). "Delete history" keeps skills,
    goals and settings.
