# GitHub release (PREPARED - not created)

- **Tag:** `v1.3.0` on the commit that results from merging PR #1 into the default branch (`claude/new-session-82qozp`).
- **Release name:** Personal Difficulty 1.3.0
- **Type:** normal release (not a pre-release, not a draft-only).
- **Assets (attach exactly these, after re-checking `sha256sum -c SHA256SUMS`):**
  - `release/1.3.0/personal-difficulty-1.3.0.jar` - 105,213 bytes - SHA-256 `7d97ae9e6a733f0178fc12a99ed5d25d400159a5a9a0c59d123aa6647cdd8919`
  - `release/1.3.0/SHA256SUMS` (also lists the sources jar; attach `personal-difficulty-1.3.0-sources.jar` too if you keep that line, otherwise GitHub's automatic source archive suffices)
- **Release notes:** the text of `RELEASE_NOTES_1.3.0.md` plus the block below.

```
## Requirements
Minecraft 26.3 - Fabric Loader 0.20.1+ - Java 25 - Fabric API 0.165.0+26.3 - dedicated server only (clients install nothing).

## Install
Put Fabric API and personal-difficulty-1.3.0.jar in the server's mods folder, start, then /personaldifficulty <mode>.
Config: config/personaldifficulty.properties, reload with /personaldifficulty admin reload.

## Verification
94 unit tests and 116 in-server GameTests pass on this exact source; the jar reproduces from a clean rebuild; a Fabric 26.3
dedicated server loads it with all 21 mixins. Automated tests used simulated players; see docs/VERIFICATION.md for what was and
was not run. No real-client test record is included in the repository.

SHA-256 (personal-difficulty-1.3.0.jar): 7d97ae9e6a733f0178fc12a99ed5d25d400159a5a9a0c59d123aa6647cdd8919
```

Steps (after you approve): merge PR #1 -> `git tag v1.3.0 <merge commit>` -> push the tag -> GitHub > Releases > Draft a new release,
choose the tag, set the title/notes, attach the assets, Publish. The repository has no release workflow (`.github/` is absent).
