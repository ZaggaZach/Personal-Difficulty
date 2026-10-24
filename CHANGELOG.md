# Changelog

## 1.0.0

First release. Minecraft 26.3, Fabric Loader 0.20.1+, Fabric API 0.165.0+26.3, Java 25. Server-side only.

* Per-player personal difficulty: INHERIT (default), NORMAL, EASY, HARD, chosen with `/personaldifficulty`.
* Incoming damage scaled once, after vanilla mitigation; exceptions for PvP, self-inflicted, void and
  administrative damage; optional per-damage-type overrides.
* Hunger scaling at the exhaustion source (including natural-regeneration cost); food and saturation untouched.
* Target-specific, per-shot wider aim for skeleton family, illusioner, drowned and pillager attacks against
  Easy players.
* Validated, commented config at `config/personaldifficulty.properties` with `/personaldifficulty admin reload`.
* UUID-keyed persistent selections in the world folder.
* Operator-only `admin status|reset|config|reload` subcommands.

Not in this release: Hard-mode ranged accuracy; piglin, witch, wither, ghast, blaze and modded shooters.
