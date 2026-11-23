# Issue #20 红色记录

## 环境

```text
cd backend && env -u WRITELAB_MODEL_API_KEY -u WRITELAB_MODEL_BASE_URL \
    -u WRITELAB_MODEL_NAME -u WRITELAB_MODEL_EMBEDDING_NAME \
    mvn -q -Pno-containers -Dmaven.repo.local=/home/xllcbc/.cache/sandcastle/m2 test
```

## AC：上传、去重、限额、错误码、分块与状态（后端）

对应测试：

- `DocumentChunkerTest`（`splitsMarkdownByHeadingPathWithTargetLengthOverlapAndOffsets`、
  `mergesShortParagraphsUnderTheSameMarkdownHeading`、`splitsLongSingleParagraphsIntoOverlappingWindows`、
  `splitsTxtByBlankLineParagraphsWithoutAHeadingPath`、`returnsNoChunksForBlankText`）
- `DocumentPropertiesTest`（`defaultsTheUploadAndChunkLimits`、`acceptsExplicitConfiguration`）
- `UserDocumentApiIntegrationTest`（`uploadsMarkdownThenReachesReadyWithChunks`、
  `returnsTheExistingDocumentForIdenticalContentWithoutAddingChunks`、`listsDocumentsNewestFirst`、
  `rejectsFilesLargerThanTwoMegabytes`、`rejectsTheFiftyFirstDocument`、
  `rejectsAnUploadThatWouldExceedTheChunkBudget`、`rejectsUnsupportedFormats`、
  `rejectsEmptyAndWhitespaceOnlyDocuments`、`rejectsNonUtf8Content`、`rejectsBinaryContent`、
  `keepsOnlyTheFileNameForDisplayAndNeverUsesItAsAPath`、`returnsStable404ForUnknownDocument`、
  `marksAProcessingFailureWithAStableErrorCodeThenProcessesTheNextUpload`）

### 真实输出（红色）

```text
[ERROR] /backend/src/test/java/com/writelab/document/DocumentChunkerTest.java:[14,49] cannot find symbol
[ERROR]   symbol:   class DocumentChunker
[ERROR] /backend/src/test/java/com/writelab/document/DocumentChunkerTest.java:[24,65] cannot find symbol
[ERROR]   symbol:   variable DocumentFormat
[ERROR] /backend/src/test/java/com/writelab/config/DocumentPropertiesTest.java:[17,13] cannot find symbol
[ERROR]   symbol:   class DocumentProperties
[ERROR] /backend/src/test/java/com/writelab/document/UserDocumentApiIntegrationTest.java:[336,25] cannot find symbol
[ERROR]   symbol:   class DefaultUserDocumentProcessor
[ERROR] /backend/src/test/java/com/writelab/document/UserDocumentApiIntegrationTest.java:[357,9] method does not override or implement a method from a supertype
[ERROR] -> [Help 1]
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.14.1:testCompile
```

## AC：前端上传、状态轮询、失败原因与列表

对应测试：`UserDocumentsPanel.test.tsx`（`shows the upload hints and an empty state ...`、
`lists each document with its status and chunk count ...`、`uploads a document and shows it immediately ...`、
`polls processing documents until READY and then stops`、`shows a stable failure reason ...`、
`explains a duplicate upload ...`、`shows an understandable message ...`）

### 真实输出（红色）

```text
 ❯ src/test/UserDocumentsPanel.test.tsx (0 test)

⎯⎯⎯⎯⎯⎯ Failed Suites 1 ⎯⎯⎯⎯⎯⎯

 FAIL  src/test/UserDocumentsPanel.test.tsx [ src/test/UserDocumentsPanel.test.tsx ]
Error: Failed to resolve import "../components/UserDocumentsPanel" from "src/test/UserDocumentsPanel.test.tsx". Does the file exist?
 Plugin: vite:import-analysis

 Test Files  1 failed | 1 passed (2)
      Tests  2 passed (2)
```

## 代码评审修复：去重响应、原子限额、分块只做一次、轮询断言

对应测试变更：

- `UserDocumentApiIntegrationTest.returnsTheExistingDocumentForIdenticalContentWithoutAddingChunks`
  断言 `409` 响应同时带回已有文档的完整元数据（`document` 字段）。
- `UserDocumentsPanel.test.tsx` 的轮询测试改为观察 UI：READY 后若继续轮询，会显示后续返回的
  FAILED 状态；不再断言依赖被调用的次数。

### 真实输出（红色）

```text
[ERROR] UserDocumentApiIntegrationTest.java:[338,21] constructor DefaultUserDocumentProcessor in class
com.writelab.document.DefaultUserDocumentProcessor cannot be applied to given types;
[ERROR] UserDocumentApiIntegrationTest.java:[342,18] FailableUserDocumentProcessor is not abstract and
does not override abstract method process(java.util.UUID,java.lang.String,com.writelab.document.DocumentFormat)
in com.writelab.document.UserDocumentProcessor
[ERROR] UserDocumentApiIntegrationTest.java:[359,9] method does not override or implement a method
from a supertype
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.14.1:testCompile
```
