# CLAUDE.md

This repo's own notes for agents are in `AGENT.md`: read it too.

<!-- pano-agent-guide:start -->
## Agent guide

You are in a **Pano plugin template** (what a new plugin is started from). Whatever you write here is copied into other plugins, so it must follow the guide exactly.

Read `agent-guide/README.md` first, then **only** the topic file it points to for your task. Decide from the
request and the code; ask only where the guide says a wrong guess is costly. `agent-guide/` is a synced copy: edit it
in `theme-core/agent-guide/` (repo `PanoMC/sdk`) and run `bun scripts/sync-agent-guide.js` there.

The three rules you will break first:

1. Every site view is a named view `<ns>:<ViewName>` with a contract version: one `.svelte` file with `export const view = {...}`, never a registration with `component` (`plugin-views.md`).
2. Declare relative API paths only; Pano serves them at `/api/plugins/<pluginId>/...` and `/api/plugins/<pluginId>/panel/...`. Lists answer `{ items }` (+ `{ page }` when paged), errors `{ error: { code } }` (`plugin-api.md`).
3. Do not add features by hand to generated output: `pano-boilerplate-plugin` is what `pano-plugin new` writes, its template is `theme-core/packages/plugin-kit/bin/templates/plugin/` (`plugin-new.md`).
<!-- pano-agent-guide:end -->
