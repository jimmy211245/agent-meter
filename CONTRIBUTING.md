# Contributing

Thanks for helping! Issues and pull requests are welcome.

## Setup

- JDK 21
- `./gradlew test` must pass; CI runs it on Linux, Windows and macOS.
- `./gradlew run` starts the app. Set `AGENT_METER_HOME=/tmp/am` to keep your real data dir untouched while testing.

## Log formats change — please send fixtures

Agent log formats are undocumented and change between versions. If something is parsed wrong:

1. Find the smallest set of lines that reproduces it.
2. **Anonymize them**: replace every prompt/response/tool text with `"<redacted>"`, replace paths with
   something like `/home/dev/demo-project`, and replace ids if you like. Never commit real prompts,
   file contents, account ids, emails or API keys.
3. Add them to `src/test/resources/fixtures/` and a test in `src/test/kotlin/`.

## Rules for parsers

- Never throw on unknown or malformed input: ignore unknown fields/types, report bad lines in `ParseResult.errors`.
- Store only numbers and metadata — no message content.
- Every event needs a key that is stable across re-imports (response/message id if the log has one).

## Prices

Price changes go to `src/main/resources/prices.json` with `asOf` and `source` filled in.
No prices in Kotlin code.

## Style

Kotlin official code style, small files, no new dependencies without a good reason.
