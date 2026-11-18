# Issue #16 红色记录

## AC4 复核：配置默认值必须与离线评测结论一致（0.60）

对应测试：`RetrievalPropertiesTest.defaultsTheSimilarityThresholdToTheTunedValueWhenNotConfigured`

`docs/retrieval-evaluation.md` 把默认阈值定为 0.60，`application.yml` 与 `.env.example` 也写 0.60，
但 `RetrievalProperties` 的 Java 兜底常量仍是 0.3。属性缺失时不会走 yml，会静默退回旧值。

### 命令

```text
cd backend && mvn -Pno-containers -Dtest=RetrievalPropertiesTest test
```

### 真实输出（红色）

```text
[ERROR] Failures: 
[ERROR]   RetrievalPropertiesTest.defaultsTheSimilarityThresholdToTheTunedValueWhenNotConfigured:16->lambda$defaultsTheSimilarityThresholdToTheTunedValueWhenNotConfigured$0:17 
expected: 0.6
 but was: 0.3
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.6:test (default-test) on project writelab-backend: There are test failures.
```
