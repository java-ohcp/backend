# 离线农技知识生产

爬虫只写文件，不参与后端启动，不直接修改线上服务。未审核的采集结果不会进入诊断知识库。

## 运行

从仓库根目录运行，Python 3.10+：

```powershell
python -m pip install -r crawler/requirements.txt
python crawler/pipeline.py --max-pages 20
# 指定文章；--url 可重复，--depth 0–2，最多 200 页
python crawler/pipeline.py --url https://example.org/replace-with-public-article --depth 0 --max-pages 1
# 无网络时重新处理已有原始 HTML
python crawler/pipeline.py --offline
```

也可逐步运行 `crawl.py`、`clean.py`、`structure.py`、`embed.py`。
原 `struct.py` 已改名为 `structure.py`，避免遮蔽 Python 标准库导致 requests 等包导入失败。
不要恢复同名旧脚本。

## 增量抓取与清洗

- 种子使用具体农技文章地址。遵循 robots.txt；404 表示没有规则，其他非成功状态保守跳过。
- 同站点串行，至少间隔 1.2 秒，也遵循更长的 robots Crawl-delay。
- 429、5xx 和连接异常最多尝试三次；拒绝跨主机跳转、非 HTML、超过 2 MiB 的正文。
- 支持 ETag / Last-Modified 条件请求与 SHA-256 内容去重。失败不覆盖此前的 HTML。
- `data/raw/*.meta.json` 记录请求地址、最终来源、抓取/检查时间、响应长度、内容指纹。
- `data/raw/run_report.json` 记录逐页成功、未变化、拒绝或失败结果；全部失败时 CLI 非零退出。
- 正文清洗去导航，保留短元素标题和段落，支持常见正文容器及文本回退，使用 SimHash 去重。

## 结构化与人工审核

候选写入 `crawler/out/candidates.jsonl`：含症状原句、施肥线索、文章 URL、时间和指纹。
按段落标题归属元素，比较多个缺素类型的句子不自动归给单一元素。
候选 ID 由文章地址和元素确定；原文指纹不变时保留人工修改，原文变化时重新要求审核。

人工校对时，必须核对来源、作物适用范围以及全部剂量/时期/单位，再填写：

- `needsReview: false`、`reviewedBy`、ISO 格式 `reviewedAt`。
- `intro`、`crops`、`colorPattern` / `morphology`、`agronomy`、`safety`。
- `organicPlan`、`chemicalPlan`；没有已核实方案时可为空列表，不得编造。
- 每个方案项需有名称（measure 或 fertilizer）、dosage、method、timing。
- 精确的公开文章 `sourceUrl`，不能只写网站首页；保留 `contentHash`。

```powershell
python crawler/structure.py --merge
```

合并在整批校验通过后原子替换知识库。重复执行不会重复添加；已重新审核的同 ID
条目可更新既有来源；冲突的重复 ID 或不完整审核会使整批失败并保留原文件。
审核字段体现人工核对记录，程序检查不能代替农艺真实性审核。

## 可选向量构建

配置 `EMBEDDING_BASE_URL`、`EMBEDDING_API_KEY`、`EMBEDDING_MODEL`，或使用不入库的
`crawler/.env`。运行 `python crawler/embed.py`。这是独立步骤，不在 pipeline 中自动调用。

模型与检索文本未变的条目复用向量；验证返回条数、索引顺序、维度、非零有限数值。
全部批次成功才原子替换 `data/vectors/vectors.jsonl`，中途失败保留旧索引。
缺少模型配置时仍可运行采集、清洗、候选提取及全部离线测试。

```powershell
python -m unittest discover -s crawler/tests -v
```

2026-10-22 实测昌都市农技推广总站一篇公开文章，提取 8 条待审核候选；重复抓取识别
内容未变。此次没有把未审核条目合并入库，也没有调用真实付费 Embedding API。
