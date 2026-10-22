# 农作物缺素症识别与科学施肥建议智能体

OHCP 限时编程赛作品。输入一段作物症状描述，输出缺素结论（带置信度与证据）与六段式科学施肥建议。

**核心主张：识别结论来自本地知识库的规则矩阵与检索打分，大模型只负责把已确定的事实组织成自然语言。** 所以模型挂了照样能演示。

---

## 快速开始

```bash
# 后端（唯一需要跑起来的进程）
cd backend
mvn spring-boot:run
# 或 IDEA 里直接 Run CropAgentApplication

# 验证
curl http://localhost:8080/api/v1/health
```

`health` 返回 `status: UP` 且 `knowledgeBase.items > 0` 即表示可用。

> 8080 被占用时（很常见），换端口启动：`java -jar target/crop-agent-1.0.0.jar --server.port=18080`

> 如果在 IDEA 里跑，工作目录是 `backend/`，`application.yml` 里的 `../data/kb` 才能指对；
> 如果用 `java -jar` 在项目根目录启动，请把 `agent.kb.dir` 改成 `./data/kb`。

---

## 环境要求

| 项 | 版本 |
|---|---|
| JDK | **推荐 21**（pom 编译目标仍是 17，只用 17 的同学也能编译运行） |
| Maven | 3.9+ |
| Python | 3.10+（仅爬虫需要，不参与启动） |

> 为什么是 21 而 pom 写 17：`<java.version>17</java.version>` 决定的是**产出字节码版本**，
> 用 21 编译、产出 17 字节码，队友有 17 也能跑。反之把 pom 改成 21 就会把只用 17 的人挡在门外。
> 别用 JDK 25：Spring Boot 3.3 的部分构建插件在 25 上兼容性还没跟上。

### IDEA 导入（第一次必看）

直接打开 `OHCP` 根目录时，IDEA 只会当成普通文件夹，**Java 文件不会高亮、依赖也解析不了**。
必须把 Maven 工程导进来：

1. 右键 `backend/pom.xml` → **Add as Maven Project**
2. 等右下角依赖下载完，`backend/src/main/java` 会变成蓝色的 Sources Root
3. Project Structure（Ctrl+Alt+Shift+S）→ Project SDK 选 **21**，Language level 选 **17**
4. Settings → Build Tools → Maven → Runner → JRE 也选 **21**（否则 `mvn spring-boot:run` 用的还是旧 JDK）

如果导入后仍然全红：**File → Invalidate Caches → Invalidate and Restart**。

不需要 Docker、不需要数据库、不需要 Redis。外部依赖只有一个可选的模型 API。

---


## 前端整合说明

前端工程已并入 `frontend/`（Vue 3 + Vite + TS），与后端**通过一层适配接口**对接：

| 项 | 说明 |
|---|---|
| 前端调用前缀 | `/api/v1/fe/*`（`frontend/.env` 里的 `VITE_API_BASE`） |
| 适配层 | `api/fe/FeController`，把后端契约转成前端 `types.ts` 的形状 |
| 为什么需要 | 前端 types.ts 自成一套字段名（matchingFeatures / introduction / greenTip…），后端契约是另一套；两边都成型，加中间层比改任意一边都省事 |
| 原始接口 | `/api/v1/diagnose` 等仍然保留，文档与 curl 测试不受影响 |

三种运行方式：

```bash
# 1) 开发：前端热更新
cd frontend && npm run dev        # 5173，代理到 8080
cd backend  && mvn spring-boot:run

# 2) 交付：单端口（已把 dist 拷进 static）
cd backend && mvn spring-boot:run

# 3) 改了前端后重新打包
cd frontend && npm run build && cp -r dist/* ../backend/src/main/resources/static/
```

## 目录结构

```
OHCP/
├─ backend/     Spring Boot，唯一对外服务
├─ crawler/     Python 离线知识生产（抓取→清洗→结构化→向量化）
├─ data/        三者唯一交汇点：知识库 / 规则矩阵 / 向量
├─ frontend/    前端（另行约定）
└─ docs/        需求、架构、技术选型
```

三条铁律：
1. 爬虫只写 `data/`，不调后端；后端只读 `data/`，不调爬虫。
2. 后端启动不依赖爬虫是否跑过——`data/` 里已有保底数据。
3. 前端不碰密钥，一切模型调用走后端。

---

## 当前进度

| 模块 | 状态 |
|---|---|
| 数据层（12 条知识 + 规则矩阵 + 思政库） | ✅ 已完成 |
| 爬虫四步链（crawl / clean / structure / embed） | ✅ 实测一篇农技公开文章，提取 8 条待审候选；向量化有离线回归测试，真实 API 需配置 |
| 后端骨架 + 统一响应 + 健康自检 | ✅ 已完成，编译通过 |
| 规则识别 + 置信度计算 + `/diagnose` | ✅ 已完成，三场景实测通过 |
| 三路混合检索（规则 + BM25 + 向量） | ✅ 三路全通，`capabilities` = rule + bm25 + vector + template |
| langchain4j（选定的智能体框架） | ✅ 已引入，落点在 `VectorIndex` 的 InMemoryEmbeddingStore |
| Spring AI + DeepSeek | ✅ 已接，启动预热，失败自动回退模板 |
| 六段式方案装配 + 事实校验 | ✅ 已完成，编造用量会被拦下 |
| SSE 流式输出（`/chat`） | ✅ 已完成 |
| 知识查询（`/knowledge/{element}`） | ✅ 已完成，供前端科普卡片 |
| 模型接入（DeepSeek） | ⬜ 未接，当前纯模板编排 |
| 多轮会话 / 风险预警 / 统计看板 | ⬜ 未做 |
| 前端 | ⬜ 由队友负责 |

> 服务在默认端口起不来时，先看是不是 8080 被占；本机环境若有 `SERVER_PORT` / `SERVER__PORT`
> 这类变量会覆盖 yml 里的端口，用 `--server.port=8081` 显式指定即可。

> 刻意让"规则识别"先于"模型接入"完成。这样即使模型环节出任何问题，
> 演示链路依然是通的——这是本次的核心风险控制策略。

---

## 接口

2026-10-22 增强：诊断增加候选分差、叶龄冲突、作物与来源门槛；未形成结论时不生成施肥方案。
知识重载使用完整快照，保留同元素的多来源证据，BM25 重建不累积旧索引。
新增 `POST /api/v1/knowledge/search`，供 `java-ohcp/Agent` 独立模块直接检索。
请求为 `{crop, query, topK}`，响应为 `{code:0, data:{matches:[{id,nutrient,crops,text,sourceUrl,score}]}}`。
score 是归一化的规则/检索匹配分数，不是标定后的诊断概率。使用说明见 [增强验证说明](docs/增强验证说明.md)。

| 方法 | 路径 | 状态 |
|---|---|---|
| GET | `/api/v1/health` | 可用 |
| GET | `/api/v1/routes` | 可用 |
| POST | `/api/v1/diagnose` | 可用 |
| POST | `/api/v1/advice` | 可用 |
| POST | `/api/v1/chat` | 可用（SSE 流式） |

契约详见 `docs/农作物缺素症智能体-前后端对接架构设计.docx`。

---

## 演示脚本（三个场景）

1. **标准命中**：`水稻，叶片从下部老叶开始均匀发黄，植株矮小，分蘖减少。` → 缺氮，置信度约 0.86
2. **易混淆鉴别**：`新叶叶脉间发黄，叶脉还是绿的` → 缺铁，展示"部位先验"如何与缺镁区分
3. **信息不足**：`叶子发黄` → 置信度不足，系统追问部位与叶龄

第三个场景最能体现克制——不硬编结论，比乱猜更能说明设计水平。

---

## 文档

| 文件 | 内容 |
|---|---|
| `docs/OHCP课程编程赛规则V0.1.md` | 赛制与评分维度 |
| `docs/技术选型V1.0.md` | 12 项技术决策与理由 |
| `docs/农作物缺素症智能体-前后端对接架构设计.docx` | 接口契约与对接规范 |
