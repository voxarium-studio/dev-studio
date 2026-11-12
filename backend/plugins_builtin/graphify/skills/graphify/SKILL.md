---
name: graphify
description: Search and inspect the code and document knowledge graph maintained by the Graphify plugin in Voxarium Studio. Use before searching indexed project code, tracing symbols and dependencies, or assessing the impact of a change.
---

# Search the project's graph

Use the graph path from the session's system prompt. The plugin keeps the index at the agent
launch root, which may differ from the working directory of an isolated checkout. Prefer
`graphify-out/merged-graph.json` when present (code and team documents), otherwise
`graphify-out/graph.json`. Pass the absolute path with `--graph` so commands inspect that index.

```sh
graphify query "<question>" --graph "<absolute graph path>"
graphify explain "<Symbol>" --graph "<absolute graph path>"
graphify path "A" "B" --graph "<absolute graph path>"
graphify affected "<Symbol>" --graph "<absolute graph path>"
```

Start with `query` for a feature or concept, `explain` for a symbol and its neighbours, `path`
for a relationship, and `affected` to assess what a change can reach. Follow the returned
file and line references and read the current source before editing it. The index describes
the main checkout and may lag changes in this session or an isolated checkout.

If the graph or CLI is unavailable, or the graph has no useful answer, continue with file
search. Use text search directly for string literals, configuration values and comments.
Report a missing or stale index when it affects your conclusion.

The plugin owns indexing: its Graphify page shows status and offers Update and Rebuild all.
Opening the project and completing a task can update it automatically. Do not reset, delete,
rebuild, or install tools just to answer a search question. Indexing documents uses the
agent CLI chosen on the Graphify page (Claude Code, Codex or Qwen Code); querying an existing graph does not require a new extraction.
