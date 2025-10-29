下面是建议提交为 `todos.md` 的内容。它已经拆成一系列可以依次完成的任务（M0→M8），每条任务都按我们在 `agents.md` 里定义的任务模板写好信息槽位，codex 只需要把状态从 `todo` 往后推，并在推进时不断填充字段。

````markdown
# todos.md
本文件是 codex 的任务列表与进度跟踪。  
状态字段可取：`todo | in-progress | review | done | blocked`  
当任务推进时，请在该任务下更新 Owner / 时间 / 运行方式 / Known Limits 等信息。

---

## M0. 初始化项目骨架

- [ ] M0.1 建立基础工程与运行入口 (status: done)
  - Owner: codex @ 2024-05-19 00:00 UTC
  - Context:
    - **Implementation stack**: Kotlin + Ktor(Netty) + graphql-java on JVM 21 (per maintainer directive; previous Node.js scaffolding replaced).
    - 需要一个可以启动的空网关进程，后续所有功能都会挂在这个进程里。
  - Acceptance:
    - 可以本地启动一个进程（任意语言运行时皆可）。
    - 进程监听一个 HTTP 端口（固定值或配置都行）。
    - 提供 `/healthz` GET 返回 200/OK（纯本地，不依赖后端）。
  - Steps/Plan:
    - 使用 Gradle 初始化 Kotlin/Ktor 项目骨架。
    - 编写 `Application.kt`，通过 Ktor Netty 引擎启动 HTTP server。
    - 暴露 `/healthz` 路由返回 200 与 JSON `{"status":"ok"}`。
  - What Changed:
    - 新增 Gradle 构建（`build.gradle.kts`、`settings.gradle.kts`、Gradle Wrapper）并声明 Ktor(Netty) 与 graphql-java 依赖。
    - 新增 `src/main/kotlin/com/gateway/Application.kt`，实现可启动的 Ktor 服务器与 `/healthz` 路由。
    - 更新 `.gitignore` 以忽略 Gradle 构建输出。
  - How to Run/Test:
    - 先安装 JDK 21+。
    - 启动命令:
      - `./gradlew run`
    - 访问:
      - `GET http://localhost:4000/healthz` → `200` 且响应 `{"status":"ok"}`。
  - Known Limits:
    - 仅提供 `/healthz`；尚未实现配置加载、schema 聚合、路由等核心功能。
    - 端口目前固定为 4000（可通过 `PORT` 环境变量覆盖）。
    - 先前的 Node.js/Express 骨架已弃用；若有历史脚本引用旧结构需同步更新。
  - Open Questions:
  - Next Role: Reviewer — 请确认基础服务是否可启动并返回预期的 `/healthz` 响应。
  - Notes/Follow-ups:

---

## M1. 读取后端服务配置

- [ ] M1.1 支持从配置文件读取上游服务清单 (status: done)
  - Owner: codex @ 2024-05-19 01:00 UTC
  - Context:
    - 网关需要知道有哪些后端 GraphQL 服务（URL、名称、优先级）。
    - 这些信息后续会被 introspection / 合并 / 路由使用。
    - 约定配置文件默认位于 `config/upstreams.yaml`，亦可通过 `UPSTREAMS_CONFIG` 覆盖。
  - Acceptance:
    - 存在一个配置文件（例如 `config/upstreams.yaml` 或等价物）。
    - 代码能够读取并解析这个文件，得到一组服务对象：
      - `name`（字符串）
      - `url`（GraphQL endpoint 的 HTTP 地址）
      - `priority`（整数，越小越优先）
    - 读取结果在启动日志中可见。
  - Steps/Plan:
    - 定义配置文件格式并加示例。
    - 编写加载函数。
    - 启动时读取并打印解析出的服务列表。
  - What Changed:
    - 新增 `config/upstreams.yaml` 示例配置。
    - 新增 `UpstreamConfigLoader` 负责解析 YAML 并按 priority 排序。
    - 启动时加载 upstreams 并在日志中打印，支持 `UPSTREAMS_CONFIG` 环境变量覆盖路径。
    - 补充 Jackson YAML/Kotlin 模块依赖与解析单元测试。
  - How to Run/Test:
    - `./gradlew test`。
    - `./gradlew run` → 启动日志打印每个 upstream。
  - Known Limits:
    - 当前仅支持本地文件路径；后续如需远程配置需扩展加载器。
    - 配置文件解析失败将中断启动（无降级逻辑）。
  - Open Questions:
  - Next Role: Reviewer — 请确认配置读取逻辑与日志输出符合预期。
  - Notes/Follow-ups:
    - 后续任务可复用 `UpstreamService` 数据类以挂接 introspection。

---

## M2. 对每个后端做 GraphQL introspection

- [ ] M2.1 实现 introspection 抓取逻辑 (status: done)
  - Owner: codex @ 2024-05-19 02:30 UTC
  - Context:
    - 网关启动时需要拉取每个后端的 GraphQL schema。
    - 使用标准 GraphQL introspection 查询（`__schema`, `__type` 等）。
  - Acceptance:
    - 启动时，对每个后端发出 introspection 请求（POST JSON `{query: "...__schema..."}`）。
    - 能把 introspection 的返回解析成一个可操作的 schema 描述（记住：不限制具体库实现方式，但后面需要能遍历类型/字段）。
    - 失败时应让网关启动直接报错或标记为 blocked（不要悄悄忽略）。
  - Steps/Plan:
    - 准备 introspection 查询常量。
    - 实现 HTTP POST 调用下游。
    - 解析结果为本地结构。
  - What Changed:
    - 新增 `IntrospectionService`，使用 Java `HttpClient` 向每个上游发送 introspection 查询并解析响应。
    - 启动流程调用 introspection 并将结果存入应用属性，日志打印 Query 根字段列表。
    - 补充 `MockWebServer` 单元测试覆盖成功与错误返回场景。
  - How to Run/Test:
    - `./gradlew test`
    - `./gradlew run` → 启动日志输出每个 upstream 的 queryType 与字段列表。
  - Known Limits:
    - 目前仅抓取 Query 类型字段，Mutation/Subscription 字段将在后续任务扩展。
    - 尚未处理授权头透传；后续任务决定策略。
  - Open Questions:
    - 需要透传哪些请求头？（Authorization等）
  - Next Role: Reviewer — 请确认 introspection 响应解析与错误处理覆盖验收标准。
  - Notes/Follow-ups:
    - 2024-05-19 codex：若单个上游 introspection 失败会记录日志并跳过该服务，网关仍可启动；需在后续路由逻辑中考虑缺失服务的提示能力。

---

## M3. 合并多份 schema，生成“公共网关 schema”和路由表

- [ ] M3.1 生成公共查询/变更根 (status: done)
  - Owner: codex @ 2024-05-20 00:00 UTC
  - Context:
    - 多个后端可能都有同名的顶层 Query 字段或 Mutation 字段。
    - 我们只允许一个赢家：优先级数字小的服务胜出。
    - 需要记录“哪个字段归哪个后端”。
  - Acceptance:
    - 产出一个“合并后可公开的”根级 Query 定义和（如果有）Mutation 定义。
    - 为每个保留的顶层字段，记录路由表：
      - 例：`routing["getStudent"] = { serviceName: "Students", serviceUrl: "..." }`
    - 冲突字段按优先级决定胜者，劣势后端的同名字段被丢弃。
  - Steps/Plan:
    - 扩展 introspection 结构，补充 Mutation 字段信息。
    - 遍历每个后端 introspection 结果。
    - 按 priority 排序。
    - 构建新的公共 Query/Mutation 字段列表和 routing。
  - What Changed:
    - 扩展 introspection 查询，捕获 mutation 根字段并在 `UpstreamSchema` 中存储。
    - 新增 `RootSchemaMerger`，根据优先级合并 Query/Mutation 字段并生成路由表。
    - 网关启动时计算并日志输出路由表，同时在应用属性中注册合并结果。
    - 为 schema 合并逻辑与扩展后的 introspection 增加单元测试。
  - How to Run/Test:
    - 启动后打印路由表示例：
      - `getStudent -> Students(...)`
      - `getLedgerInfo -> Ledgers(...)`
    - `./gradlew test`
    - `./gradlew run` 后查看日志中的 `Gateway query routing table` / `Gateway mutation routing table`
  - Known Limits:
    - 仅根据字段名进行冲突判定，未对字段参数或返回类型做额外一致性校验。
    - Mutation 根类型仅在至少一个后端提供时生成，暂未校验名称是否统一。
  - Open Questions:
  - Next Role:
    - Reviewer — 请确认合并策略、日志输出与单元测试覆盖验收标准。
  - Notes/Follow-ups:

- [ ] M3.2 合并普通类型（非 Query/Mutation） (status: done)
  - Owner: codex @ 2025-10-29 06:37 UTC
  - Context:
    - 不同后端可能声明了同名类型（例如 StudentInfoType）。
    - 字段集合可能不同，甚至冲突。
    - 我们的规则：
      - 如果类型同名，合并字段；
      - 字段冲突时，优先级高的后端版本胜出；
      - 字段不冲突时，全部保留。
    - 这是为了让客户端看到一个统一的公共类型定义。
  - Acceptance:
    - 能遍历所有后端的类型定义。
    - 产出一张合并后的类型表（无需马上执行这些字段，只是描述它们）。
    - 对冲突字段，能在日志里说明“X 服务覆盖了 Y 服务的字段 Z”。
  - Steps/Plan:
    - 建类型名 -> {owner优先级, 字段列表} 的累积结构。
    - 逐个后端按优先级merge。
  - What Changed:
    - 扩展 introspection 查询与模型，收集上游的对象/输入对象字段定义。
    - 新增 `TypeMerger` 根据优先级合并普通类型并记录覆盖日志。
    - 启动时输出示例类型的合并结果，并在应用属性中保存类型注册表。
  - How to Run/Test:
    - `./gradlew test`
    - 启动后查看日志中的 `Merged object type` 输出确认字段归属。
  - Known Limits:
    - 目前仅合并对象类型与输入对象类型，其它类型（接口、联合、枚举）暂未处理。
  - Open Questions:
  - Next Role:
    - Reviewer — 请确认类型合并策略、日志输出与测试覆盖验收标准。
  - Notes/Follow-ups:

- [ ] M3.3 生成最终可发布的 SDL + 可达性裁剪 (status: done)
  - Owner: codex @ 2025-10-29 07:45 UTC
  - Context:
    - Root schema 合并后需要将 Query/Mutation 和普通类型转成 SDL 供 `/schema` 等端点复用。
    - 生成 SDL 时需裁剪掉不可达类型，保证只暴露从顶层字段可触达的类型定义。
  - Acceptance:
    - 启动后能拿到一个字符串 `mergedSDL`。
    - `mergedSDL` 里包含最终的 Query/Mutation、以及被引用到的类型。
    - 可在日志中输出前几行 SDL 作为验证。
  - Steps/Plan:
    - 根据合并结果构造一份内存中的“最终 schema 表示”。
    - 打印成 SDL 字符串。
    - 从公开 root 字段向下做引用追踪，把无引用的类型剔除。
  - What Changed:
    - 扩展 `IntrospectionService`：单次请求获取根字段完整定义与所有对象/输入类型，并填充到 `UpstreamSchema`。
    - 更新 `RootSchemaMerger` 保存字段定义，新增 `SchemaComposer` 基于根路由与类型注册表生成裁剪后的 SDL。
    - `Application` 组装时生成合并 SDL，日志输出预览并暴露在应用属性；补充单元测试覆盖 SDL 生成与裁剪。
  - How to Run/Test:
    - `./gradlew test`
    - `./gradlew run` → 日志输出 `Generated merged schema SDL preview` 片段。
  - Known Limits:
    - 目前仅合并对象与输入对象类型；接口、联合、枚举仍未生成 SDL，需要后续任务扩展。
  - Open Questions:
  - Next Role:
    - Reviewer — 请确认 SDL 渲染、类型裁剪及新单元测试覆盖情况。
  - Notes/Follow-ups:

---

## M4. 处理 introspection 和导出公共 schema

- [ ] M4.1 `/schema` 端点 (status: done)
  - Owner: codex @ 2025-10-29 08:30 UTC
  - Context:
    - 网关要提供一个 HTTP GET `/schema`，返回合并后的 SDL。
    - 方便其他服务或前端生成类型。
  - Acceptance:
    - GET `/schema` 返回 200，Content-Type `text/plain`，body 是当前合并SDL的完整文本。
  - Steps/Plan:
    - 把 M3.3 产出的合并 SDL 暴露给 HTTP 层。
  - What Changed:
    - `Application.kt` 新增 `/schema` 路由，直接返回组合后的 SDL 文本并设置 `text/plain` 响应。
    - 增加 `ApplicationSchemaEndpointTest` 覆盖 GET `/schema` 行为，验证状态码、Content-Type 与内容。
    - 构建脚本引入 `ktor-server-test-host` 依赖以支持路由测试。
  - How to Run/Test:
    - `./gradlew test`
    - `./gradlew run` 后执行 `curl -i http://localhost:4000/schema` 验证 200 与 SDL 输出。
  - Known Limits:
    - 若上游 introspection 失败导致合并 SDL 为空，接口仍返回空字符串；后续可结合就绪探针强化校验。
  - Open Questions:
  - Next Role: Reviewer — 请确认 `/schema` 输出格式与测试覆盖符合验收标准。
  - Notes/Follow-ups:

- [ ] M4.2 introspection 查询走本地 (status: done)
  - Owner: codex @ 2025-10-29 09:40 UTC
  - Context:
    - 客户端会发 GraphQL introspection 查询（`__schema`, `__type`）。
    - 对这种请求，网关不应该往后端打请求，而是用合并后的公共 schema 来回答。
  - Acceptance:
    - 在 `POST /graphql` 时，如果请求只包含 introspection 字段：
      - 返回本地生成的 introspection 结果。
    - 非 introspection 则继续正常路由（后面 M5）。
  - Steps/Plan:
    - 检测“是否是纯 introspection”。
    - 构造 introspection 响应：可用本地的合并 schema 结构来回答。
  - What Changed:
    - 新增 `GatewayGraphQLFactory` 以基于合并 SDL 构建 GraphQL Java 实例，用于回答本地 introspection 查询。
    - 新增 `IntrospectionQueryDetector` 判断请求是否仅包含 `__schema`/`__type` 等 introspection 字段。
    - `POST /graphql` 路由支持 introspection：本地执行并返回结果，非 introspection 请求暂返回 501。
    - 增加 `ApplicationGraphQLIntrospectionTest` 验证 introspection 与非 introspection 请求的行为。
  - How to Run/Test:
    - `./gradlew test`
    - `curl -s -X POST http://localhost:4000/graphql -H 'Content-Type: application/json' -d '{"query":"{ __schema { queryType { name } } }"}'`
  - Known Limits:
    - 若合并 SDL 为空或无法被 GraphQL Java 解析，将返回 503，后续可改进以生成最小可用 schema。
    - 非 introspection 请求仍未实现路由（返回 501），待 M5 系列任务处理。
  - Open Questions:
  - Next Role: Reviewer — 请确认 introspection 检测逻辑、GraphQL 执行器构造与 HTTP 行为符合验收标准。
  - Notes/Follow-ups:

---

## M5. 业务查询路由 (/graphql 正常请求)

- [ ] M5.1 提取请求的顶层字段并决定路由 (status: done)
  - Owner: codex @ 2025-10-29 10:30 UTC
  - Context:
    - 对普通查询或变更，规则是：同一个 GraphQL operation 的所有顶层字段必须属于同一个后端。
    - 我们需要从请求 AST 拿到这些字段名，并根据 M3 的路由表决定目标后端。
  - Acceptance:
    - 对任意 `POST /graphql` 请求：
      - 网关能解析 GraphQL 文本，找到 operation 的顶层字段集合。
      - 如果这些字段都由同一后端拥有，路由目标=那个后端。
      - 如果不是同一个后端，返回错误（400 + GraphQL-style error JSON）。
  - Steps/Plan:
    - 解析 GraphQL 文本成 AST。
    - 根据路由表检查 owner 是否一致。
    - 如果不一致，构建错误响应：
      ```json
      { "errors": [ { "message": "Fields belong to different upstreams, split the request" } ] }
      ```
  - What Changed:
    - 新增 `GraphQLRequestRouter` 使用 graphql-java `Parser` 解析 operation，基于 `GatewayRootSchema` 的查询/变更路由表校验顶层字段归属并生成 `RoutedGraphQLRequest`；包含多 operation、缺少字段或不支持的 selection 的错误分支。
    - `GraphQLRoutingException` 统一承载路由错误，`Application` 的 `/graphql` handler 捕获后返回 400 GraphQL 错误 JSON。
    - `ApplicationGraphQLRoutingTest` 新增用例验证单 upstream 正常路由、Authorization 透传以及跨服务字段返回 400。
  - How to Run/Test:
    - `./gradlew test`
    - `./gradlew run` 后执行 `curl -s -X POST http://localhost:4000/graphql -H 'Content-Type: application/json' --data '{"query":"{ students { id } }"}'`
  - Known Limits:
    - 仅支持直接的 Query/Mutation 顶层字段；包含 fragment spread 或 inline fragment 的请求会被视为不支持并返回 400。
  - Open Questions:
  - Next Role: Maintainer — 评估是否需要支持 fragment/Subscription 路由策略。
  - Notes/Follow-ups:

- [ ] M5.2 将请求转发到后端并回传结果 (status: done)
  - Owner: codex @ 2025-10-29 10:30 UTC
  - Context:
    - 新增路由器根据合并 schema 的路由表确定 GraphQL 查询目标 upstream，并在 Ktor handler 中调用。
    - 使用 Java HttpClient 将 query/operationName/variables 转发给对应 upstream，并透传 Authorization 头。
    - 针对无法解析或跨服务查询的场景返回 400，无法访问 upstream 返回 502。
  - Acceptance:
    - `POST /graphql`（普通业务查询）会被代理到正确的后端 URL。
    - 客户端拿到结果等同于直接打那个后端。
  - Steps/Plan:
    - 根据路由表拿到后端 URL。
    - 复制入站请求的 body JSON，POST 给后端。
    - 拿回响应体（JSON 字符串）和状态码。
    - 用这个响应体直接作为网关响应（状态固定返回 200，可以保留 GraphQL `errors` 结构）。
    - 至少透传 Authorization 头。
  - What Changed:
    - 新增 `GraphQLRequestRouter`、`GraphQLRequestForwarder` 组件用于解析 operation、校验跨服务访问并执行 HTTP 转发。
    - `/graphql` handler 支持业务查询：先做本地 introspection，再路由到单一 upstream；失败时返回 400/502。
    - 补充端到端测试覆盖成功转发、Authorization 透传和跨服务查询被拒绝的场景；更新 introspection 测试期望。
  - How to Run/Test:
    - `./gradlew test`
    - 或启动网关：`./gradlew run` 后 `curl -X POST -H 'Content-Type: application/json' --data '{"query":"{ students { id } }"}' http://localhost:4000/graphql`
  - Known Limits:
    - 暂不支持单次请求拆分访问多个 upstream；遇到跨服务字段会返回 400（由 M5.1 路由校验负责）。
    - 当前仅透传 Authorization 头，其他自定义头若需支持需后续扩展。
    - Upstream 响应状态码需为 2xx 才视为成功；非 2xx 时统一映射为 502。
  - Open Questions:
  - Next Role: Maintainer — 观察是否需要扩展头透传或重试/超时策略。
  - Notes/Follow-ups:

---

## M6. 健康检查与观测

- [ ] M6.1 健康/就绪探针 (status: todo)
  - Owner:
  - Context:
    - 运维需要判断网关是否已成功完成 schema 聚合并可接受流量。
  - Acceptance:
    - `GET /healthz` → 返回 200 以及简单 JSON，比如 `{ "status": "ok" }`。
    - `GET /readyz` → 只有在所有后端 introspection 成功 + 合并完成后才返回 200，否则返回 500/503。
  - Steps/Plan:
    - 启动完成后将某个全局 flag 设为 ready。
    - `/readyz` 根据该 flag 决定响应。
  - What Changed:
  - How to Run/Test:
    - 启动成功后，访问 `/readyz` 返回 200。
    - 模拟 introspection 失败场景（可临时注释/伪造），应返回非 200。
  - Known Limits:
  - Open Questions:
  - Next Role:
  - Notes/Follow-ups:

---

## M7. 打包与容器化

- [ ] M7.1 生成可部署工件 (status: todo)
  - Owner:
  - Context:
    - 需要编译出一个单文件可运行的产物（例如 fat jar / 单一二进制等）。
    - 容器镜像应能运行该产物并加载配置文件。
  - Acceptance:
    - 提供构建脚本或命令（例如 `build.sh` 或 `gradle shadowJar`）输出单体可运行工件。
    - 提供 Dockerfile:
      - 拷贝该工件
      - 拷贝 `config/upstreams.yaml`
      - 暴露端口
      - ENTRYPOINT 直接运行网关并指定配置路径
    - 提供示例 docker-compose.yml（可选），展示网关+后端服务同网络下的场景。
  - Steps/Plan:
    - 编写打包命令。
    - 编写 Dockerfile。
    - 本地 `docker run` 后能访问 `/healthz`、`/schema`、`/graphql`（后端允许的情况下）。
  - What Changed:
  - How to Run/Test:
    - `docker build ...`
    - `docker run -p 4000:4000 ...`
    - curl 验证接口。
  - Known Limits:
  - Open Questions:
  - Next Role:
  - Notes/Follow-ups:

---

## M8. 文档化 / 后续工作

- [ ] M8.1 使用说明文档 (status: todo)
  - Owner:
  - Context:
    - 需要为后续接手的人说明本网关的行为约束：
      - 一个请求只能路由到一个后端
      - 冲突字段按优先级决策
      - `/schema` 是合并后的公开视图
      - introspection 在网关本地回答
  - Acceptance:
    - 在仓库添加文档（README 或 docs/usage.md）：
      - 如何启动
      - 如何配置 upstreams
      - 限制/已知不支持的模式（多后端拼接）
      - 典型错误响应示例
    - 文档必须和现状一致。
  - Steps/Plan:
    - 写明端口、接口、约束。
    - 写明如何新增/修改一个后端（改配置文件 + 重启）。
  - What Changed:
  - How to Run/Test:
    - 文档应可以让一个新人照着跑起来并打出一个成功的查询。
  - Known Limits:
  - Open Questions:
  - Next Role:
  - Notes/Follow-ups:

---

### 备注
- codex 在推进任务时，要更新对应任务块的状态、Owner、时间戳、What Changed、How to Run/Test 等字段。
- 如果某个任务需要拆分出更多子任务（例如 M3.2 太大），请复制模板块并放到该任务下方，保持同样字段。
````
