package com.writelab.evaluation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One on-demand gate report. Evaluations record their observed metrics and cases here; the report
 * is printed as Markdown, written to {@code target/agent-eval/<gate>.md} for the run record, and
 * turns every failed threshold into one assertion failure. Evaluation runs are opt-in, so a red
 * gate never slows the default test suite.
 */
public final class GateReport {

    private final String gate;
    private final String title;
    private final List<Metric> metrics = new ArrayList<>();
    private final List<CaseResult> cases = new ArrayList<>();
    private final Map<String, String> notes = new LinkedHashMap<>();

    public GateReport(String gate, String title) {
        this.gate = gate;
        this.title = title;
    }

    public void note(String name, String value) {
        notes.put(name, value);
    }

    public void metric(String name, Object observed, String threshold, boolean pass) {
        metrics.add(new Metric(name, String.valueOf(observed), threshold, pass));
    }

    public void caseResult(String name, boolean pass, String detail) {
        cases.add(new CaseResult(name, pass, detail));
    }

    /**
     * Writes the report, prints it, then fails with the same Markdown when any metric or case is
     * red. The thrown message is the report so an on-demand run leaves the evidence in the log.
     */
    public void assertPassed() {
        String markdown = markdown();
        write(markdown);
        System.out.println(markdown);
        boolean failed = metrics.stream().anyMatch(metric -> !metric.pass())
                || cases.stream().anyMatch(result -> !result.pass());
        if (failed) {
            throw new AssertionError("评测未通过阈值：\n" + markdown);
        }
    }

    private String markdown() {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(title).append("\n\n");
        builder.append("- Gate: `").append(gate).append("`\n");
        builder.append("- 日期: ").append(LocalDate.now()).append("\n");
        builder.append("- 运行方式: `tools/agent-platform-eval.sh ").append(gate).append("`")
                .append("（按需运行，不在默认测试套件中）\n");
        for (Map.Entry<String, String> note : notes.entrySet()) {
            builder.append("- ").append(note.getKey()).append(": ").append(note.getValue()).append("\n");
        }
        builder.append("\n## 阈值\n\n");
        builder.append("| 指标 | 观测值 | 阈值 | 结果 |\n");
        builder.append("| --- | --- | --- | --- |\n");
        for (Metric metric : metrics) {
            builder.append("| ").append(metric.name())
                    .append(" | ").append(metric.observed())
                    .append(" | ").append(metric.threshold())
                    .append(" | ").append(metric.pass() ? "通过" : "失败")
                    .append(" |\n");
        }
        if (!cases.isEmpty()) {
            builder.append("\n## 评测用例\n\n");
            builder.append("| 用例 | 结果 | 说明 |\n");
            builder.append("| --- | --- | --- |\n");
            for (CaseResult result : cases) {
                builder.append("| ").append(result.name())
                        .append(" | ").append(result.pass() ? "通过" : "失败")
                        .append(" | ").append(result.detail())
                        .append(" |\n");
            }
        }
        return builder.toString();
    }

    private void write(String markdown) {
        Path directory = Path.of("target", "agent-eval");
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(gate + ".md"), markdown);
        } catch (IOException exception) {
            throw new IllegalStateException("无法写入评测报告: " + directory, exception);
        }
    }

    private record Metric(String name, String observed, String threshold, boolean pass) {
    }

    private record CaseResult(String name, boolean pass, String detail) {
    }
}
