# Herdr Bot Frontend

Angular with ThreadPlane native components and the AG-UI adapter. See the
[root README](../README.md) for prerequisites, profiles and ./dev.sh startup.

## Local checks

    TPLANE_TELEMETRY_DISABLED=1 npm ci --ignore-scripts
    npm test
    npm run test:guard
    npm run build

npm test runs component / application tests and retained historical SSE parser
utility tests. Production chat uses ThreadPlane and the official AG-UI client.
Dependency verification also uses npm ls and npm audit.

## Browser verification

The current suites require a running frontend and a verified fake-model backend:

    node e2e/threadplane-chat.mjs
    node e2e/threadplane-navigation.mjs
    node e2e/threadplane-bot.mjs

To run the chat suite at mobile width:

    CHAT_WIDTH=375 node e2e/threadplane-chat.mjs

Chat defaults to 1280px. The mobile-specific check runs at 375px; the scroll
check supports 375px and 1280px. CHAT_WIDTH accepts integer widths from 320 to
3840, but other widths do not satisfy that scroll check.

BROWSER_URL defaults to http://127.0.0.1:4300, BACKEND_URL to
http://127.0.0.1:8181. For ./dev.sh --mock, use ports 4200 and 8080.
CHROME_PATH overrides the default macOS Chrome executable.

The shared helper verifies all three SAFE_MOCK fields through both origins,
guards generation, retains synthetic fixtures, and writes results, screenshots,
console and network evidence to a temporary directory. Any failed check makes
the suite fail. Scripts never clean the database.

| Suite | Coverage |
| --- | --- |
| Chat | Raw drafts and delayed creation, concurrent Runs, cancellation and persisted Message IDs, status reconciliation, historical failure / cancellation, clipboard, controlled failure and input cases |
| Navigation | Native Sidenav and palette, title search, pinning, pagination recovery, narrow layouts, keyboard focus, stale responses and error recovery |
| Bot | Native status select, search, pagination, create / update validation, save refresh recovery, dialogs and narrow layouts |

Fault injection is explicitly marked in each script. OFF uses a synthetic GraphQL
fixture and IME uses composition events; they are not native OS IME validation.
Clipboard checks distinguish simulated rejection from actual Chrome write / read.
Browser suites use public GraphQL state; the backend suite separately tests real
PostgreSQL ownership and Session consistency. Cross-owner browser login is not
simulated.

The Bot suite creates 21 pagination fixtures by default. BOT_PAGINATION_TAG can
reuse an existing group named <tag>-page-00 through -20. Chat and navigation suites
also retain their own synthetic data.

Older verify-ticket-*, verify-seed, verify-draft, verify-native-ui,
verify-clipboard, verify-pinning* and issue16/18/22 layout scripts are retired and
fail immediately. Their assertions are preserved for traceability; use the three
ThreadPlane suites above for current verification.
