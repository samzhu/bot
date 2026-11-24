# Herdr Bot Frontend

Angular with ThreadPlane native components and the AG-UI adapter. See the
[root README](../README.md) for prerequisites, profiles and ./dev.sh startup.
Read the [frontend development guide](DEVELOPMENT.md) before planning, changing,
or reviewing frontend work.

## Local checks

    TPLANE_TELEMETRY_DISABLED=1 npm ci --ignore-scripts
    npm run lint
    npm test
    npm run test:guard
    npm run build

`npm run build` runs lint and `npm run test:layout` before compiling. `npm test`
runs component / application tests and retained historical SSE parser
utility tests. Production chat uses ThreadPlane and the official AG-UI client.
Dependency verification also uses npm ls and npm audit.

This project invokes ESLint directly through npm. The Angular CLI's `ng lint`
command delegates to a configured lint builder. [Angular CLI lint reference](https://angular.dev/cli/lint)

## Browser verification

After `npm run build`, run `npm run test:layout:browser` to measure the rendered
app shell in Chrome using isolated read-only fixtures. The check serves the
build and fixtures through browser interception and needs no running services.
Set `CHROME_PATH` if Chrome is installed in a nonstandard location. The
[frontend development guide](DEVELOPMENT.md#建置與驗收) documents what this check covers.

The integration suites start and stop their own test Fake backend and isolated
PostgreSQL through `bootTestRun`. Build the frontend first; Docker, Java 25 and
Chrome are required. No existing backend or frontend origin may be supplied.

    node e2e/threadplane-chat.mjs
    node e2e/threadplane-navigation.mjs
    node e2e/threadplane-bot.mjs
    node e2e/empty-conversation-46.mjs
    node e2e/monthly-budget-default-47.mjs
    node e2e/threadplane-usage.mjs

To run the chat suite at mobile width:

    CHAT_WIDTH=375 node e2e/threadplane-chat.mjs

Chat defaults to 1280px. The mobile-specific check runs at 375px; the scroll
check supports 375px and 1280px. `CHAT_WIDTH` accepts integer widths from 320 to
3840, but other widths do not satisfy that scroll check. `CHROME_PATH` overrides
the default macOS Chrome executable. The runner rejects externally configured
origins and provider settings, restricts browser requests to its own origin,
and writes results, screenshots, console and network evidence to a temporary
directory. Its fixture database is disposed with the test-owned container.

| Suite | Coverage |
| --- | --- |
| Chat | Raw drafts and delayed creation, concurrent Runs, cancellation and persisted Message IDs, status reconciliation, historical failure / cancellation (DOM tests), clipboard, controlled failure and input cases |
| Navigation | Native Sidenav and palette, title search, pinning, pagination recovery, narrow layouts, keyboard focus, stale responses and error recovery |
| Bot | Native status select, search, pagination, create / update validation, save refresh recovery, dialogs and narrow layouts |

Fault injection is explicitly marked in each script. OFF uses a synthetic GraphQL
fixture and IME uses composition events; they are not native OS IME validation.
Clipboard checks distinguish simulated rejection from actual Chrome write / read.
Browser suites use public GraphQL state; the backend suite separately tests real
PostgreSQL ownership and Session consistency. Cross-owner browser login is not
simulated.

The Bot and navigation suites create their own synthetic pagination data in the
fixture database. Each invocation starts with a fresh database. The Usage suite
explicitly inserts its own simulated data into the owned PostgreSQL container and
temporarily toggles only its marked membership. The fixture is stopped after the
suite; the database is never selected from an external origin.

Older verify-ticket-*, verify-seed, verify-draft, verify-native-ui,
verify-clipboard, verify-pinning* and issue16/18/22 layout scripts are retired and
fail immediately. Their assertions are preserved for traceability; use the three
ThreadPlane suites above for current verification.
