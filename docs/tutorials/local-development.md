# Start the local development environment

Check the [project prerequisites](../../README.md#1-prerequisites--environment) before starting. This guide ends with the Angular app accessible at `http://127.0.0.1:4200/`.

## Local Gemini key

Gemini needs a key. If `backend/config/application-secrets.properties` already exists, use that file. Otherwise, from the repository root, create a private copy without overwriting an existing file:

```bash
(umask 077; set -C; cat backend/config/application-secrets.properties.example > backend/config/application-secrets.properties)
```

Privately set `bot-google-api-key` and keep permissions at `0600`; do not print the key or put it in command history. From `backend`, Spring Boot loads `config/application-dev.yaml`, whose guarded optional import reads that file. Missing or blank keys fail at startup for Gemini. OS environment variables, Java system properties and command-line properties take precedence over config data. Do not commit secrets.

## Start the local development environment

With Java 25, Node.js 26, and Docker available, install the frontend dependencies once, then run the launcher from the repository root:

```bash
(cd frontend && TPLANE_TELEMETRY_DISABLED=1 npm ci --ignore-scripts)
./dev.sh
```

The launcher waits for the backend on `127.0.0.1:8080` to report readiness before starting Angular. Open `http://127.0.0.1:4200/`. Press Ctrl+C to stop both application processes. If either exits, the launcher stops the other.

The backend selects the `dev` profile and defaults `SPRING_AI_MODEL_CHAT` to `google-genai` when the variable is unset. It uses the existing Gemini key file described above. To use local Ollama instead:

```bash
SPRING_AI_MODEL_CHAT=ollama ./dev.sh
```

The launcher accepts no arguments. An explicitly set model selector is passed through to Spring, including invalid or empty values. To run the backend on its own with other Spring configuration, invoke Gradle from `backend`:

```bash
cd backend
./gradlew bootRun --args="--spring.profiles.active=dev --spring.ai.model.chat=google-genai"
```

Spring Boot starts or connects the Compose database and Grafana, then runs Liquibase against PostgreSQL. See [Database startup](../../README.md#database-startup).

Boot leaves the selected Compose services running when the app exits. To stop the database and Grafana, run `docker compose stop` from `backend/`. If your Compose file defines an Ollama service, use `docker compose --profile ollama stop ollama` to stop that service, or `docker compose --profile ollama stop` to stop all selected services. These commands preserve volumes.

## Local Ollama

Choose local Ollama with standard Spring settings:

```bash
cd backend
./gradlew bootRun --args="--spring.profiles.active=dev --spring.ai.model.chat=ollama"
```

Ollama defaults to `http://localhost:11434`. To use another host, set `SPRING_AI_OLLAMA_BASE_URL=http://ollama-host:11434` when starting the app. Start an independently managed Ollama daemon before the application. If `backend/compose.yaml` defines the Ollama service, Compose stores downloaded models in the `ollama-models` volume and sets `OLLAMA_NO_CLOUD=1`. The configured model is `llama3.2:1b`. Explicitly download it once the service is running. For a Compose-managed service, run the following from `backend/`:

```bash
docker compose exec ollama ollama pull llama3.2:1b
```

For native Ollama, use `ollama pull llama3.2:1b`.

The [Ollama model page](https://ollama.com/library/llama3.2:1b) lists the model at approximately 1.3 GB (checked 2026-11-23). Allow additional disk space and memory for inference; requirements depend on context and runtime. Initial image and model downloads need network access. If the service or model is unavailable, requests fail rather than switching provider or silently fetching a model. Actual model availability and hardware performance have not been validated by the automated tests.

Local Ollama attempts are recorded separately from Gemini. Spring AI 2.1 does not expose enough raw information to distinguish a missing token count from a real zero, so usage is reported as unavailable/incomplete and cost remains unpriced. Existing budget admission rules still apply.

For routine checks, see the [backend development guide](../../backend/DEVELOPMENT.md) and [frontend development guide](../../frontend/DEVELOPMENT.md).
