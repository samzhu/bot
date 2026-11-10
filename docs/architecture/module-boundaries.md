# Backend Architecture & Module Boundaries (Pre-Modulith)

> Document Version: 1.0 (Gate A)
> Baseline: Java 25 (toolchain 25), Gradle 9.8.2, Spring Boot 4.1.2, Spring AI 2.1.0-RC2

## 1. Overview & Architectural Goals

To prepare for future Spring Modulith adoption without introducing premature framework overhead, the backend codebase is partitioned into distinct functional modules located as direct child packages under `io.github.samzhu.bot`.

### Core Rules:
1. **Public vs Internal Separation**: Every module exposes only facade interfaces and DTO records at the module root (`io.github.samzhu.bot.<module>`). All database entities, Spring Data repositories, internal service implementations, and web controllers reside strictly within `<module>.internal.*`.
2. **Encapsulation & No Cross-Module Internal Imports**: A module must never import another module's `.internal` classes. Inter-module interactions occur strictly via root facade interfaces.
3. **Strict Directionality**: Dependencies flow in a single direction without cycles:
   - `execution` $\to$ `conversation` $\to$ `bot`
   - `execution` $\to$ `bot`
   - All modules $\to$ `identity`
   - `conversation` **never** imports `execution` Java classes.
4. **Bounded Operations**: Public list and query operations require pagination parameters (`limit`, `offset`) and clamp bounds (default 20, max 100) within implementations.

---

## 2. Module Breakdown

```
io.github.samzhu.bot
├── bootstrap
│   └── DatabaseInitializer.java
├── identity
│   ├── CurrentUserProvider.java (Public Facade)
│   ├── ProfileGuard.java (Public Listener)
│   └── internal/
│       ├── DevCurrentUserProvider.java
│       └── LoopbackFilter.java
├── bot
│   ├── BotOperations.java (Public Facade)
│   ├── BotView.java (Public DTO)
│   └── internal/
│       ├── Bot.java (JDBC Entity)
│       ├── BotRepository.java
│       ├── BotOperationsImpl.java
│       └── web/
│           └── BotGraphQLController.java
├── conversation
│   ├── ConversationOperations.java (Public Facade)
│   ├── ConversationView.java (Public DTO)
│   ├── MessageView.java (Public DTO)
│   └── internal/
│       ├── Conversation.java (JDBC Entity)
│       ├── Message.java (JDBC Entity)
│       ├── ConversationRepository.java
│       ├── MessageRepository.java
│       ├── ConversationOperationsImpl.java
│       └── web/
│           └── ConversationGraphQLController.java
└── execution
    ├── ExecutionOperations.java (Public Facade)
    ├── RunStatus.java (Public Enum)
    ├── RunView.java (Public DTO)
    └── internal/
        ├── AgentRun.java (JDBC Entity)
        ├── AgentRunRepository.java
        ├── RunHandle.java (Deep Lifecycle Module)
        ├── RunRegistry.java
        ├── RunExecutionServiceImpl.java
        ├── memory/
        │   └── SessionConfig.java (Spring AI Session JDBC)
        ├── model/
        │   ├── MockChatModel.java
        │   └── GoogleGenAiManualConfig.java
        └── web/
            ├── RunController.java (REST API)
            └── ConversationRunsGraphQLController.java (GraphQL runs resolver)
```

### Module Responsibilities

| Module | Public Boundary | Internal Components | Dependencies |
| :--- | :--- | :--- | :--- |
| **`identity`** | `CurrentUserProvider`, `ProfileGuard` | `DevCurrentUserProvider`, `LoopbackFilter` | None (Foundation) |
| **`bot`** | `BotOperations`, `BotView` | `Bot`, `BotRepository`, `BotOperationsImpl`, `BotGraphQLController` | `identity` |
| **`conversation`**| `ConversationOperations`, `ConversationView`, `MessageView` | `Conversation`, `Message`, `ConversationRepository`, `MessageRepository`, `ConversationOperationsImpl`, `ConversationGraphQLController` | `identity`, `bot` |
| **`execution`** | `ExecutionOperations`, `RunStatus`, `RunView` | `AgentRun`, `AgentRunRepository`, `RunExecutionServiceImpl`, `RunHandle`, `RunRegistry`, `SessionConfig`, `MockChatModel`, `GoogleGenAiManualConfig`, `RunController`, `ConversationRunsGraphQLController` | `identity`, `bot`, `conversation` |
| **`bootstrap`** | `DatabaseInitializer` | Pure JDBC runner for V1-V3 SQL migrations & orphan recovery | Independent |

---

## 3. Boundary Cycle Prevention: Conversation and Execution

In the GraphQL schema, a `Conversation` type includes a `runs` field (`runs(limit: Int, offset: Int): [AgentRun!]!`).

If `ConversationGraphQLController` were to query runs directly, `conversation` would depend on `execution`, creating a bidirectional coupling.

**Solution**:
- `ConversationGraphQLController` (in `conversation.internal.web`) handles only `conversations`, `conversation`, and `messages`.
- `ConversationRunsGraphQLController` (in `execution.internal.web`) declares:
  ```java
  @SchemaMapping(typeName = "Conversation", field = "runs")
  public List<RunView> runs(ConversationView conversation, ...)
  ```
  This resolves `Conversation.runs` using `ExecutionOperations` while keeping the dependency strictly one-way: `execution` $\to$ `conversation`.
- In the database schema, `message.run_id` is stored as a UUID string foreign key without an object relation or Java class dependency.

---

## 4. Configuration & Model Resolution (Real-AI vs Mock)

- **Manual Bean Registration**: All Google GenAI auto-configurations are explicitly excluded in `application.yaml`:
  ```yaml
  spring:
    autoconfigure:
      exclude:
        - org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration
        - ... (9 excluded Google GenAI auto-configurations)
    ai:
      vectorstore:
        type: none
        pgvector:
          initialize-schema: false
      model:
        audio:
          speech: none
  ```
- **Model Property Resolution**:
  - `GoogleGenAiManualConfig` reads exclusively `${spring.ai.google.genai.chat.model:}` with no `GOOGLE_GENAI_MODEL` environment variable fallback.
  - Fail-fast validation ensures `real-ai` profile is accompanied by non-empty API key and explicit chat model.
  - External configuration via `config/application-real-ai.yaml` is excluded by `.gitignore` across all locations.

---

## 5. Future Spring Modulith Verification Seam

When `@ApplicationModuleTest` or `ApplicationModules.of(BotApplication.class).verify()` is introduced:
1. `identity`, `bot`, `conversation`, `execution`, and `bootstrap` map 1:1 to named Spring Modulith modules.
2. The package structure complies with Modulith's package encapsulation rules (`*.internal.*` are private to the module).
3. The dependency graph forms a Directed Acyclic Graph (DAG) with zero circular dependencies.
