# Baropa's GE Firemaker

Lines logs east to west at the Grand Exchange, the way a real player would - scanning nearby
tiles for the longest clear stretch and burning the whole thing in one pass, rather than working
off a fixed grid or a hardcoded set of tiles.

---

## Features

| Feature                     | Description                                                                                                  |
|------------------------------|----------------------------------------------------------------------------------------------------------------|
| **Dynamic line planning**    | Scans a window of rows around the GE bank booth for the longest walkable, fire-free stretch and commits to it as one line, picking randomly among equally-good rows so it doesn't always plant itself on the exact same tiles. |
| **Bank-proximity awareness** | Excludes the ring of tiles right next to the bank booth where OSRS blocks firemaking, and permanently blacklists any tile the game rejects for the rest of the session. |
| **Self-restocking**          | Withdraws a tinderbox (if missing) and logs (if missing) from the bank in one visit, forcing the "item" (unnoted) withdraw mode so noted logs from a previous GE trade don't go unrecognised. |
| **All log types**            | Normal through redwood. |
| **Anti-ban**                 | Uses this client's `Rs2Antiban` firemaking template plus log-normal reaction-time delays instead of flat sleeps. |
| **Debug overlay**            | Optional toggle adds current state, target tile, walk/light failure counts, and the bank's withdraw-as-note toggle to the overlay - useful for diagnosing "why isn't it doing anything" without digging through logs. |

---

## Requirements
- Microbot RuneLite client
- A tinderbox and logs of your chosen type in the bank
- Start the plugin while standing at the Grand Exchange, near a bank booth - it does not walk you there

---

## Configuration Options
- **Log type**: which log to burn
- **Max line length**: upper limit on fires per line (defaults to 27, a full inventory)
- **Restock amount**: how many logs to withdraw per bank visit
- **Enable anti-ban**: randomized reaction times, mouse movement, and micro-breaks
- **Show debug info**: adds state machine and bank/tile diagnostics to the overlay

---

## Disclaimer
This script is intended for use within the **Microbot RuneLite Client** only.
Use of automation software in Old School RuneScape is against Jagex's rules and can result in
penalties to your account. Use at your own risk.

---

## Changelog
### 1.0.0
- Initial release
