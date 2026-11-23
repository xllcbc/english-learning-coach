# 操作文档：User Document 上传、就绪与生命周期

“我的文档”面板让学习者上传自己的 `.md` / `.txt` 学习材料。文档会在后台解析、分块，进入 `READY` 后才算可用；之后可以停用/启用、删除、重试失败任务，或用修改后的同名文档原地替换旧内容。教师讲解引用见 #22，向量索引见 #23。

## 上传方式

界面：在“我的文档”面板选择 UTF-8 的 `.md` 或 `.txt`，点击“上传文档”。上传区域会提示“仅支持 TXT/Markdown、UTF-8”和“请勿上传敏感信息”。

HTTP：

```bash
curl -F "file=@grammar-notes.md" http://localhost:8080/api/documents
```

成功返回 `202 Accepted`：

```json
{ "id": "c0f2b6a1-...", "status": "UPLOADED" }
```

相同内容的文档重复上传返回 `409 Conflict` 与已有文档标识，不新增文档或 Document Chunk；响应同时带回该文档的完整元数据：

```json
{
  "code": "DUPLICATE_DOCUMENT",
  "message": "相同内容的文档已经存在。",
  "documentId": "c0f2b6a1-...",
  "document": {
    "id": "c0f2b6a1-...",
    "filename": "grammar-notes.md",
    "status": "READY",
    "active": true,
    "charCount": 1200,
    "chunkCount": 3,
    "errorCode": null,
    "replacementStatus": "NONE",
    "replacementErrorCode": null,
    "createdAt": "2026-11-23T08:00:00Z",
    "updatedAt": "2026-11-23T08:00:02Z"
  }
}
```

## 启用、停用、删除与重试

```bash
# 停用 / 启用：只改变 active，不重新分块，也不删除内容
curl -X PATCH http://localhost:8080/api/documents/<document-id> \
  -H 'Content-Type: application/json' -d '{"active": false}'

# 删除：文档与其 Document Chunk 一起消失，历史引用快照不受影响
curl -X DELETE http://localhost:8080/api/documents/<document-id>

# 重试 FAILED 文档或恢复中断的处理：幂等，不会新增文档或 chunk
curl -X POST http://localhost:8080/api/documents/<document-id>/retry
```

`PATCH` 返回更新后的完整文档元数据；`DELETE` 返回 `204 No Content`；`retry` 返回 `202 Accepted` 与当前文档元数据。重复停用/启用或重复重试都是幂等的。`retry` 优先恢复待切换的替换 generation，其次重试 `FAILED` 或进程中断留下的未完成 generation；已经 `READY` 且没有待替换内容时返回当前元数据，不重复处理。缺少已落库 chunk 的历史失败文档返回 `409 DOCUMENT_RETRY_UNAVAILABLE`，请删除后重新上传。面板在停用后显示“已停用”，失败文档显示“重试”。

## 同名替换

修改后重新上传同名文件时，系统复用同一个 User Document（`id` 不变），不会新增同名文档：

1. 新内容写入一个新的 `generation` 的 Document Chunk；
2. 此时旧内容仍是 `active_generation`，文档保持 `READY`，`replacementStatus` 为 `PROCESSING`；
3. 全部 chunk 就绪后，在一个事务里切换 `active_generation`、更新内容哈希与字符数，再删除旧 generation；
4. 任一步失败时，旧内容继续可用，`status` 仍为 `READY`，`replacementStatus` 变为 `FAILED`，`replacementErrorCode` 给出原因；点击“重试”只处理待切换的 generation。
5. 上一次替换仍在进行或已失败时，再次上传同名文件会取消待切换的 generation 并重新开始替换；替换中不会永久阻塞。

完全相同内容重复上传仍然返回 `409 DUPLICATE_DOCUMENT`，不会触发新的 generation。替换成功并删除旧 generation 后，旧内容不再保留内容哈希；再次上传旧内容会按一次新的上传处理（同名则再次替换，不同名则新建文档）。

## 如何确认文档已就绪

状态依次经过：

| 状态 | 含义 |
| --- | --- |
| `UPLOADED` | 已接收，等待后台处理 |
| `PARSING` | 正在解析文本 |
| `CHUNKING` | 正在切分 Document Chunk |
| `EMBEDDING` | 保留给 REAL 模式；Mock 模式直接进入 `READY` |
| `READY` | 已就绪，可以参与后续检索 |
| `FAILED` | 处理失败，查看失败原因与错误码 |

三种确认方式：

1. “我的文档”面板显示“已就绪”，并显示分块数量（如“3 个分块”）。
2. 轮询单个文档：

   ```bash
   curl http://localhost:8080/api/documents/<document-id>
   ```

   当 `status` 为 `READY` 且 `chunkCount` 大于 0 时，文档已就绪。
3. 打开文档列表：

   ```bash
   curl http://localhost:8080/api/documents
   ```

列表按创建时间倒序返回。前端在文档处理中或 `replacementStatus` 为 `PROCESSING` 时每 1.5 秒轮询一次，进入终态后停止轮询。

## 限额

| 项目 | 默认值 | 配置项 |
| --- | --- | --- |
| 单文件 | 2 MB | `WRITELAB_DOCUMENT_MAX_FILE_BYTES` |
| User Document 数量 | 50 篇 | `WRITELAB_DOCUMENT_MAX_DOCUMENTS` |
| Document Chunk 总数 | 5,000 个 | `WRITELAB_DOCUMENT_MAX_CHUNKS` |
| 分块目标长度 | 300–500 tokens | `WRITELAB_DOCUMENT_CHUNK_MIN_TOKENS` / `WRITELAB_DOCUMENT_CHUNK_MAX_TOKENS` |
| 分块重叠 | 约 15% | `WRITELAB_DOCUMENT_CHUNK_OVERLAP_RATIO` |

超限返回 `413 Payload Too Large` 与稳定错误码。分块总数取所有文档 `chunkCount` 之和与实际 `document_chunk` 行数的较大者，待切换的 replacement generation 也计入预算；上传前就会检查，不会先处理再失败。上传请求先做预算检查再写库，预算检查与插入在同一个事务里串行化，避免并发上传同时越过文档数或分块数上限。

## 稳定错误码

| HTTP | 错误码 | 含义 |
| --- | --- | --- |
| `413` | `FILE_TOO_LARGE` | 超过单文件 2 MB |
| `413` | `DOCUMENT_LIMIT_REACHED` | 已达到 50 篇上限 |
| `413` | `CHUNK_LIMIT_REACHED` | 本次上传会使分块总数超过 5,000 |
| `415` | `UNSUPPORTED_FILE_TYPE` | 不是 `.md` 或 `.txt` |
| `422` | `EMPTY_DOCUMENT` | 空文件或纯空白 |
| `422` | `INVALID_ENCODING` | 不是有效 UTF-8 |
| `422` | `BINARY_DOCUMENT` | 看起来是二进制内容 |
| `409` | `DUPLICATE_DOCUMENT` | 相同内容已存在，响应带 `documentId` |
| `409` | `DOCUMENT_RETRY_UNAVAILABLE` | 历史失败文档没有已落库 chunk，需删除后重新上传 |
| `400` | `INVALID_DOCUMENT_UPDATE` | `PATCH` 缺少 `active` |
| `404` | `DOCUMENT_NOT_FOUND` | 文档不存在 |
| — | `PROCESSING_FAILED` | 后台处理失败，保存在文档的 `errorCode` 中 |
| — | `replacementErrorCode` | 替换失败原因；此时 `status` 仍是 `READY` |

`FAILED` 文档在面板中显示“失败原因”和错误码。失败文档不参与后续检索：教师讲解的检索接缝（#22）只查询 `READY` 且 `active` 的文档。替换失败的文档仍以旧 generation 参与检索，面板显示“替换失败”和重试入口。

## 分块规则

- Markdown 按 `#` 到 `######` 标题层级分节，节内按空行段落切分；每块保存完整标题路径（例如 `Grammar > Subject-verb agreement`）。
- TXT 按空行段落切分，标题路径为空。
- 超长段落再按句子切分；一个段落仍超长时按 token 上限硬切。
- 过短块会与同标题下的相邻块合并，合并后不超过上限。
- 每块保存 `headingPath`、`content`、`startOffset`、`endOffset`；偏移量指向规范化后的解析文本。
- 分块器版本保存在 `chunkerVersion`（默认 `document-chunker-v1`）。

## 去重与存储

- 内容哈希是规范化解析文本的 SHA-256；重复上传返回已有文档，不重复分块。
- 只保存解析后的 Document Chunk；不保留原始上传文件，也不额外保存一份文档全文。
- `filename` 只用于展示，会去掉路径部分，不参与文件系统拼接。
- 普通日志只记录文档标识、状态、chunk 数和错误码；不记录文档全文、完整提示词或 API key。
- 上传内容是不可信数据：后续进入教师讲解时只作为引用材料，不执行其中的指令。

## 测试

```bash
cd backend
env -u WRITELAB_MODEL_API_KEY -u WRITELAB_MODEL_BASE_URL \
    -u WRITELAB_MODEL_NAME -u WRITELAB_MODEL_EMBEDDING_NAME \
    mvn -q -Pno-containers test

cd ../frontend
npm test
```

需要 Docker 的 `UserDocumentApiIntegrationTest` 覆盖上传到 `READY`、去重、限额、错误码、失败状态、停用/启用、删除、重试、同名替换成功/失败与替换中互斥；前端 `UserDocumentsPanel` 组件测试覆盖上传、列表、轮询、失败原因、重复内容提示、停用/启用、删除确认、重试和替换状态。容器测试需要本机 Docker 正在运行；`-Pno-containers` 只跳过这些容器测试，不会跳过前端组件测试。
