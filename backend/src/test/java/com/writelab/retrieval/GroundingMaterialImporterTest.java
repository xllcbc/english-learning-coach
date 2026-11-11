package com.writelab.retrieval;

import com.writelab.feedback.IssueCategory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class GroundingMaterialImporterTest {

    @Test
    void parsesQuotedRow() {
        GroundingMaterial material = GroundingMaterialImporter.parse(
                "\"grammar\",\"主谓一致：主语是第三人称单数时，动词加 -s。\",\"WriteLab 自建依据材料\",\"CC BY 4.0\"", 2);

        assertThat(material.category()).isEqualTo(IssueCategory.GRAMMAR);
        assertThat(material.content()).isEqualTo("主谓一致：主语是第三人称单数时，动词加 -s。");
        assertThat(material.source()).isEqualTo("WriteLab 自建依据材料");
        assertThat(material.license()).isEqualTo("CC BY 4.0");
        assertThat(material.id()).isEqualTo(UUID.nameUUIDFromBytes(
                ("GRAMMAR\n主谓一致：主语是第三人称单数时，动词加 -s。").getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void derivesTheSameIdForTheSameContent() {
        GroundingMaterial first = GroundingMaterialImporter.parse(
                "\"COHERENCE\",\"段落只围绕一个中心句展开。\",\"WriteLab 自建依据材料\",\"CC BY 4.0\"", 2);
        GroundingMaterial second = GroundingMaterialImporter.parse(
                "\"COHERENCE\",\"段落只围绕一个中心句展开。\",\"WriteLab 自建依据材料\",\"CC BY 4.0\"", 9);

        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void rejectsUnknownIssueCategory() {
        assertThatIllegalStateException()
                .isThrownBy(() -> GroundingMaterialImporter.parse(
                        "\"SPELLING\",\"拼写规则。\",\"WriteLab 自建依据材料\",\"CC BY 4.0\"", 7))
                .withMessageContaining("第 7 行");
    }

    @Test
    void rejectsRowWithUnexpectedColumnCount() {
        assertThatIllegalStateException()
                .isThrownBy(() -> GroundingMaterialImporter.parse("\"GRAMMAR\",\"Only two\"", 7))
                .withMessageContaining("第 7 行");
    }

    @Test
    void bundledDatasetCoversEveryIssueCategoryWithSelfAuthoredNotes() {
        List<GroundingMaterial> rows = GroundingMaterialImporter.readRows();

        assertThat(rows).hasSizeGreaterThan(10);
        // Two entries per category is what the deterministic retrieval threshold expects as a floor.
        assertThat(java.util.Arrays.stream(IssueCategory.values()).toList()).allSatisfy(category ->
                assertThat(rows).filteredOn(row -> row.category() == category).hasSizeGreaterThanOrEqualTo(2));
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.content()).isNotBlank();
            assertThat(row.source()).isEqualTo("WriteLab 自建依据材料");
            assertThat(row.license()).isEqualTo("CC BY 4.0");
        });
    }
}
