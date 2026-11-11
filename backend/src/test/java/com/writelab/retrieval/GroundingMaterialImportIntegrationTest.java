package com.writelab.retrieval;

import com.writelab.feedback.IssueCategory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "writelab.model.api-key="
)
@Testcontainers
class GroundingMaterialImportIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @Autowired
    private GroundingMaterialImporter importer;

    @Autowired
    private LearningMaterialRetriever retriever;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void repeatedStartupDoesNotWriteRowsAgain() {
        long before = count();

        importer.run(null);

        assertThat(before).isGreaterThan(10);
        assertThat(count()).isEqualTo(before);
    }

    @Test
    void restoresMissingRowsWhenTheTableFallsBelowTheDatasetSize() {
        long before = count();
        jdbcClient.sql("DELETE FROM grounding_material WHERE category = 'COHERENCE'").update();
        assertThat(count()).isLessThan(before);

        importer.run(null);

        assertThat(count()).isEqualTo(before);
    }

    @Test
    void startupImportWritesTheDatasetIdsItselfDerives() {
        List<GroundingMaterial> dataset = GroundingMaterialImporter.readRows();

        assertThat(dataset).allSatisfy(row -> assertThat(jdbcClient.sql(
                        "SELECT count(*) FROM grounding_material WHERE id = :id")
                .param("id", row.id())
                .query(Long.class)
                .single()).isEqualTo(1));
    }

    @Test
    void startupRetrievalGroundsEveryIssueCategory() {
        for (IssueCategory category : IssueCategory.values()) {
            assertThat(retriever.groundingMaterials(List.of(category)))
                    .as("category %s", category)
                    .isNotEmpty()
                    .allSatisfy(material -> {
                        assertThat(material.category()).isEqualTo(category);
                        assertThat(material.source()).isEqualTo("WriteLab 自建依据材料");
                        assertThat(material.license()).isEqualTo("CC BY 4.0");
                    });
        }
    }

    private long count() {
        return jdbcClient.sql("SELECT count(*) FROM grounding_material").query(Long.class).single();
    }
}
