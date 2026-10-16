## Agent Meter {{TAG}}

A local desktop dashboard that shows token usage, costs and rate limits of your AI coding agents in one place.

### Supported agents
- Claude Code
- Codex
- OpenCode
- DeepSeek Harness
- Antigravity (app + IDE)

### Features
- Costs per day, week, month, agent, model and project
- Codex rate limits with warnings at 80 % / 95 % (configurable)
- Tray icon with live status and notifications
- Editable price table with price periods and DeepSeek peak/off-peak pricing
- Live file watcher

### Privacy
Runs 100 % locally. Only numbers and metadata are stored, never your prompts. No telemetry, no network access.

### Install
- Windows: `AgentMeter-{{VERSION}}.msi`
- macOS (Apple silicon): `AgentMeter-{{VERSION}}.dmg`
- Linux: `agentmeter_{{VERSION}}_amd64.deb`
- Or any OS with Java 21: the `.jar` files

The builds are not code-signed yet, so Windows SmartScreen and macOS will show a warning on first launch.

Costs are API list prices, not your subscription bill.
