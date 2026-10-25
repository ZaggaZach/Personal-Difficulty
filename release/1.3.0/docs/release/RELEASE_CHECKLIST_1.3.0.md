# Release checklist - Personal Difficulty 1.3.0 (release candidate)

State at preparation: branch `feature/1.3.0-elite-hunted`, code commit `7a9b5a729144d60df619d9d87fc86fbf1a0cdf7a`
(parent `c230e54d39540443bb58e11fb6798102310ba9af`). No tag, GitHub release or Modrinth project exists.

## Artifacts
- [x] `release/1.3.0/personal-difficulty-1.3.0.jar` - 105,213 bytes - SHA-256 `7d97ae9e6a733f0178fc12a99ed5d25d400159a5a9a0c59d123aa6647cdd8919`
- [x] `release/1.3.0/personal-difficulty-1.3.0-sources.jar` - 62,976 bytes - SHA-256 `fb70e4b1ec4d9015ced62c5d0c78b2c6725b6c890c598f18f7bd96708d492dc8`
- [x] `release/1.3.0/SHA256SUMS` matches; a clean rebuild from the committed source reproduced the identical JAR hash.
- [x] Changelog: `CHANGELOG.md` (1.3.0 section). Release notes: `docs/release/RELEASE_NOTES_1.3.0.md`.
- [x] Verification: `docs/VERIFICATION.md` (automated tests vs not-run items stated explicitly).

## Compatibility
- Minecraft 26.3 only; Fabric Loader >= 0.20.1; Java 25; Fabric API 0.165.0+26.3 (built/tested with); server-side only.
- No claims for other Minecraft versions, other loaders (Quilt/NeoForge/Forge untested), or real-client behaviour.

## Before publishing (owner actions)
1. Review the branch on GitHub (and decide whether to open a PR / merge; not done).
2. Decide the open questions in `docs/release/MODRINTH_LISTING_1.3.0.md`.
3. Create the Git tag `v1.3.0` on the merged commit and a GitHub release attaching the JAR + SHA256SUMS (not done; the repo has no
   release workflow - there is no `.github/` directory, so this is a manual step).
4. Create the Modrinth project as a draft, upload the JAR as a version (fabric, 26.3, Fabric API required), submit for review.
5. Optionally record your own playtest notes; none are included here.
