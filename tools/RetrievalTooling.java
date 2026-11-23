import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline retrieval tooling for WriteLab. It is deliberately outside Maven and CI: it needs a
 * live PostgreSQL with pgvector plus a real embedding provider, and it is run by hand when the
 * corpus changes or the grounding budget is re-tuned.
 *
 * <pre>
 *   java -cp &lt;postgresql.jar&gt; tools/RetrievalTooling.java build [--table all|corpus|grounding]
 *       [--refresh] [--limit N] [--batch-size N] [--concurrency N]
 *   java -cp &lt;postgresql.jar&gt; tools/RetrievalTooling.java stats
 *   java -cp &lt;postgresql.jar&gt; tools/RetrievalTooling.java checksum
 *   java -cp &lt;postgresql.jar&gt; tools/RetrievalTooling.java eval [--thresholds 0.5,0.6,0.7]
 *       [--per-category 1,2,3] [--max-hits 4,6,8]
 *   java -cp &lt;postgresql.jar&gt; tools/RetrievalTooling.java documents [--refresh] [--limit N]
 *       [--batch-size N]
 *   java -cp &lt;postgresql.jar&gt; tools/RetrievalTooling.java eval-documents [--threshold 0.55]
 *       [--max-hits 4] [--per-document 2] [--cases tools/retrieval-eval]
 * </pre>
 *
 * Configuration comes from the same environment variables the backend uses:
 * SPRING_DATASOURCE_URL / SPRING_DATASOURCE_USERNAME / SPRING_DATASOURCE_PASSWORD,
 * WRITELAB_MODEL_BASE_URL / WRITELAB_MODEL_API_KEY / WRITELAB_MODEL_EMBEDDING_NAME, and
 * optionally WRITELAB_MODEL_EMBEDDING_URL for a provider whose endpoint is not base + /embeddings.
 */
public final class RetrievalTooling {

    private static final int DIMENSIONS = 512;
    private static final int DEFAULT_BATCH_SIZE = 10;
    private static final int DEFAULT_CONCURRENCY = 4;
    private static final int MAX_ATTEMPTS = 10;
    private static final Set<String> CATEGORIES = Set.of(
            "GRAMMAR", "VOCABULARY", "SENTENCE_STRUCTURE", "COHERENCE", "PUNCTUATION");
    private static final Pattern EMBEDDING_ARRAY = Pattern.compile("\"embedding\"\\s*:\\s*\\[([^\\]]*)]");

    private RetrievalTooling() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        Map<String, String> options = options(Arrays.copyOfRange(args, 1, args.length));
        switch (args[0]) {
            case "build" -> build(options);
            case "stats" -> stats();
            case "checksum" -> checksum(options);
            case "eval" -> evaluate(options);
            case "documents" -> rebuildDocuments(options);
            case "eval-documents" -> evaluateDocuments(options);
            default -> {
                usage();
                System.exit(2);
            }
        }
    }

    private static void usage() {
        System.err.println("""
                usage:
                  RetrievalTooling build [--table all|corpus|grounding] [--refresh] [--limit N]
                                         [--batch-size N] [--concurrency N]
                  RetrievalTooling stats
                  RetrievalTooling checksum
                  RetrievalTooling eval [--thresholds 0.5,0.6,0.7] [--per-category 1,2,3]
                                        [--max-hits 4,6,8] [--cases tools/retrieval-eval]
                  RetrievalTooling documents [--refresh] [--limit N] [--batch-size N]
                  RetrievalTooling eval-documents [--threshold 0.55] [--max-hits 4]
                                        [--per-document 2] [--cases tools/retrieval-eval]
                """);
    }

    // ---------------------------------------------------------------- build

    private static void build(Map<String, String> options) throws Exception {
        String table = options.getOrDefault("table", "all");
        boolean refresh = options.containsKey("refresh");
        long limit = options.containsKey("limit") ? Long.parseLong(options.get("limit")) : Long.MAX_VALUE;
        int batchSize = Integer.parseInt(options.getOrDefault("batch-size", Integer.toString(DEFAULT_BATCH_SIZE)));
        int concurrency = Integer.parseInt(
                options.getOrDefault("concurrency", Integer.toString(DEFAULT_CONCURRENCY)));
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("--batch-size 必须在 1..100 之间");
        }
        if (concurrency < 1 || concurrency > 32) {
            throw new IllegalArgumentException("--concurrency 必须在 1..32 之间");
        }

        EmbeddingApi embeddings = EmbeddingApi.fromEnvironment();
        long startedAt = System.nanoTime();
        long updated = 0;
        if (table.equals("all") || table.equals("corpus")) {
            updated += indexTable(Table.corpus(), refresh, limit, batchSize, concurrency, embeddings);
        }
        if (table.equals("all") || table.equals("grounding")) {
            updated += indexTable(Table.grounding(), refresh, limit, batchSize, concurrency, embeddings);
        }
        long millis = (System.nanoTime() - startedAt) / 1_000_000;
        System.out.printf(Locale.ROOT,
                "index build finished: %d rows written in %.1f s (model=%s, dimensions=%d)%n",
                updated, millis / 1000.0, embeddings.model(), DIMENSIONS);
        stats();
    }

    private static long indexTable(
            Table table,
            boolean refresh,
            long limit,
            int batchSize,
            int concurrency,
            EmbeddingApi embeddings
    ) throws Exception {
        long startedAt = System.nanoTime();
        List<Row> rows = readRows(table, refresh, limit);
        System.out.printf(Locale.ROOT, "%s: %d rows to embed (refresh=%s, batch=%d, concurrency=%d)%n",
                table.name(), rows.size(), refresh, batchSize, concurrency);
        if (rows.isEmpty()) {
            return 0;
        }

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CompletionService<BatchResult> completion = new ExecutorCompletionService<>(executor);
        int next = 0;
        int inFlight = 0;
        int maxInFlight = concurrency * 2;
        long written = 0;
        long completed = 0;

        try (Connection connection = connection()) {
            while (next < rows.size() || inFlight > 0) {
                while (next < rows.size() && inFlight < maxInFlight) {
                    int from = next;
                    int to = Math.min(rows.size(), from + batchSize);
                    List<Row> batch = List.copyOf(rows.subList(from, to));
                    completion.submit(embeddingTask(table, batch, embeddings));
                    next = to;
                    inFlight++;
                }
                Future<BatchResult> future = completion.take();
                inFlight--;
                BatchResult result = future.get();
                updateRows(connection, table, result.rows(), result.vectors());
                written += result.rows().size();
                completed += result.rows().size();
                long elapsed = System.nanoTime() - startedAt;
                if (completed % 1000 < batchSize || completed == rows.size()) {
                    double perSecond = completed / Math.max(elapsed / 1_000_000_000.0, 0.001);
                    long remaining = rows.size() - completed;
                    long etaSeconds = perSecond <= 0 ? 0 : (long) (remaining / perSecond);
                    System.out.printf(Locale.ROOT, "%s: %d/%d rows (%.1f rows/s, eta %d s)%n",
                            table.name(), completed, rows.size(), perSecond, etaSeconds);
                }
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
        return written;
    }

    private static Callable<BatchResult> embeddingTask(Table table, List<Row> rows, EmbeddingApi embeddings) {
        return () -> {
            List<String> texts = rows.stream().map(table::embeddingText).toList();
            return new BatchResult(rows, embeddings.embed(texts));
        };
    }

    private static List<Row> readRows(Table table, boolean refresh, long limit) throws SQLException {
        String where = refresh ? "" : " WHERE embedding IS NULL";
        String sql = "SELECT id, " + table.textColumn() + " FROM " + table.name() + where + " ORDER BY id";
        if (limit != Long.MAX_VALUE) {
            sql += " LIMIT " + limit;
        }
        List<Row> rows = new ArrayList<>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                rows.add(new Row(resultSet.getString("id"), resultSet.getString(table.textColumn())));
            }
        }
        return rows;
    }

    private static void updateRows(Connection connection, Table table, List<Row> rows, List<float[]> vectors)
            throws SQLException {
        if (rows.size() != vectors.size()) {
            throw new IllegalStateException("embedding 返回的向量数量与输入不一致");
        }
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + table.name() + " SET embedding = CAST(? AS halfvec) WHERE id = ?")) {
            for (int index = 0; index < rows.size(); index++) {
                float[] vector = vectors.get(index);
                if (vector == null || vector.length != DIMENSIONS) {
                    throw new IllegalStateException("embedding 返回的向量维度不是 " + DIMENSIONS);
                }
                statement.setString(1, vectorLiteral(vector));
                statement.setObject(2, java.util.UUID.fromString(rows.get(index).id()));
                statement.addBatch();
            }
            statement.executeBatch();
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    // ---------------------------------------------------------------- stats

    private static void stats() throws SQLException {
        try (Connection connection = connection()) {
            String dbSize = queryString(connection, "SELECT pg_size_pretty(pg_database_size(current_database()))");
            System.out.println("database size: " + dbSize);
            for (Table table : List.of(Table.corpus(), Table.grounding())) {
                long rows = queryLong(connection, "SELECT count(*) FROM " + table.name());
                long embedded = queryLong(connection,
                        "SELECT count(*) FROM " + table.name() + " WHERE embedding IS NOT NULL");
                long total = queryLong(connection,
                        "SELECT pg_total_relation_size('" + table.name() + "')");
                long indexes = queryLong(connection,
                        "SELECT pg_indexes_size('" + table.name() + "')");
                System.out.printf(Locale.ROOT,
                        "%s: rows=%d embedded=%d heap+toast+indexes=%s indexes=%s%n",
                        table.name(), rows, embedded, human(total), human(indexes));
            }
            System.out.println("indexes:");
            try (PreparedStatement statement = connection.prepareStatement("""
                            SELECT tablename, indexname, pg_size_pretty(pg_relation_size(indexname::regclass))
                            FROM pg_indexes
                            WHERE tablename IN ('corpus_sentence', 'grounding_material')
                            ORDER BY tablename, indexname
                            """);
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    System.out.printf(Locale.ROOT, "  %s.%s = %s%n",
                            resultSet.getString(1), resultSet.getString(2), resultSet.getString(3));
                }
            }
        }
    }

    private static void checksum(Map<String, String> options) throws SQLException {
        String wordForm = options.get("word-form");
        try (Connection connection = connection()) {
            for (Table table : List.of(Table.corpus(), Table.grounding())) {
                boolean corpus = table.name().equals("corpus_sentence");
                String filter = wordForm == null || !corpus ? "" : " WHERE word_form = ?";
                try (PreparedStatement statement = connection.prepareStatement("""
                                SELECT count(*) AS rows,
                                       count(embedding) AS embedded,
                                       bit_xor(hashtextextended(
                                           id::text || ':' || coalesce(embedding::text, ''), 0)) AS checksum
                                FROM %s%s
                                """.formatted(table.name(), filter))) {
                    if (wordForm != null && corpus) {
                        statement.setString(1, wordForm);
                    }
                    try (ResultSet resultSet = statement.executeQuery()) {
                        resultSet.next();
                        System.out.printf(Locale.ROOT, "%s%s: rows=%d embedded=%d checksum=%s%n",
                                table.name(), wordForm == null ? "" : "[" + wordForm + "]",
                                resultSet.getLong("rows"), resultSet.getLong("embedded"),
                                resultSet.getString("checksum"));
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- eval

    private static void evaluate(Map<String, String> options) throws Exception {
        Path caseDirectory = Path.of(options.getOrDefault("cases", "tools/retrieval-eval"));
        List<CorpusCase> corpusCases = readCorpusCases(caseDirectory.resolve("corpus_cases.csv"));
        List<GroundingCase> groundingCases = readGroundingCases(caseDirectory.resolve("grounding_cases.csv"));
        List<Double> thresholds = doubles(options.getOrDefault("thresholds", "0.30,0.40,0.50,0.55,0.60,0.65,0.70"));
        List<Integer> perCategory = integers(options.getOrDefault("per-category", "1,2,3"));
        List<Integer> maxHits = integers(options.getOrDefault("max-hits", "4,6,8"));
        double defaultThreshold = doubleEnv("WRITELAB_GROUNDING_SIMILARITY_THRESHOLD", 0.6);
        int defaultPerCategory = intEnv("WRITELAB_GROUNDING_PER_CATEGORY_HITS", 2);
        int defaultMaxHits = intEnv("WRITELAB_GROUNDING_MAX_HITS", 6);

        EmbeddingApi embeddings = EmbeddingApi.fromEnvironment();
        try (Connection connection = connection()) {
            Map<String, float[]> queryVectors = embedQueries(embeddings, corpusCases, groundingCases);

            System.out.println("== Corpus Sentence retrieval ==");
            System.out.printf(Locale.ROOT,
                    "cases=%d retrieval_rate=%.1f%% top1_expected=%.1f%% top3_expected=%.1f%% "
                            + "top1_contains_word_form=%.1f%% avg_candidates=%.1f%n",
                    corpusCases.size(),
                    100.0 * rate(corpusCases, c -> retrievedCorpus(connection, c, queryVectors).size() > 0),
                    100.0 * rate(corpusCases, c -> expectedRank(
                            retrievedCorpus(connection, c, queryVectors), c.expectedSentences()) <= 1),
                    100.0 * rate(corpusCases, c -> expectedRank(
                            retrievedCorpus(connection, c, queryVectors), c.expectedSentences()) <= 3),
                    100.0 * rate(corpusCases, c -> {
                        List<RankedSentence> rankings = retrievedCorpus(connection, c, queryVectors);
                        return !rankings.isEmpty() && containsWordForm(rankings.getFirst().sentence(), c.wordForm());
                    }),
                    corpusCases.stream()
                            .mapToInt(c -> retrievedCorpus(connection, c, queryVectors).size())
                            .average().orElse(0));

            System.out.println();
            System.out.println("== Grounding Material retrieval ==");
            System.out.printf(Locale.ROOT,
                    "default settings: threshold=%.2f per-category=%d max-hits=%d%n",
                    defaultThreshold, defaultPerCategory, defaultMaxHits);
            System.out.println("threshold  per-cat  max-hits  hit-rate  expected-hit  expected-precision  avg-materials");
            for (double threshold : thresholds) {
                for (int perCategoryHits : perCategory) {
                    for (int overallHits : maxHits) {
                        if (perCategoryHits > overallHits) {
                            continue;
                        }
                        Metric metric = groundingMetric(connection, groundingCases, queryVectors,
                                perCategoryHits, overallHits, threshold);
                        System.out.printf(Locale.ROOT,
                                "%9.2f  %7d  %8d  %8.1f%%  %11.1f%%  %17.1f%%  %13.1f%n",
                                threshold, perCategoryHits, overallHits,
                                100.0 * metric.hitRate(), 100.0 * metric.expectedHitRate(),
                                100.0 * metric.expectedPrecision(), metric.averageMaterials());
                    }
                }
            }

            Metric defaultMetric = groundingMetric(connection, groundingCases, queryVectors,
                    defaultPerCategory, defaultMaxHits, defaultThreshold);
            System.out.printf(Locale.ROOT, "%ndefault: hit-rate=%.1f%% expected-hit=%.1f%% "
                            + "expected-precision=%.1f%% avg-materials=%.1f%n",
                    100.0 * defaultMetric.hitRate(), 100.0 * defaultMetric.expectedHitRate(),
                    100.0 * defaultMetric.expectedPrecision(), defaultMetric.averageMaterials());
            System.out.println();
            System.out.println("grounding case detail at default settings:");
            for (GroundingCase evalCase : groundingCases) {
                List<RankedMaterial> materials = retrievedGrounding(connection, evalCase, queryVectors,
                        defaultPerCategory, defaultMaxHits, defaultThreshold);
                String top = materials.isEmpty() ? "<none>" : materials.getFirst().content();
                long expected = materials.stream()
                        .filter(material -> evalCase.expectedContents().stream()
                                .anyMatch(expectedContent -> material.content().contains(expectedContent)))
                        .count();
                System.out.printf(Locale.ROOT, "  categories=%s materials=%d expected=%d top1=%.2f %s%n",
                        evalCase.categories(), materials.size(), expected,
                        materials.isEmpty() ? 0 : materials.getFirst().similarity(),
                        top.length() > 30 ? top.substring(0, 30) + "..." : top);
            }
            System.out.println();
            System.out.println("corpus case detail (rank of any expected sentence, 0 = not in candidates):");
            for (CorpusCase evalCase : corpusCases) {
                List<RankedSentence> rankings = retrievedCorpus(connection, evalCase, queryVectors);
                int rank = expectedRank(rankings, evalCase.expectedSentences());
                String top = rankings.isEmpty() ? "<none>" : rankings.getFirst().sentence();
                System.out.printf(Locale.ROOT, "  %-12s rank=%d candidates=%d top1=%s%n",
                        evalCase.wordForm(), rank, rankings.size(), top);
            }
        }
    }

    private static Map<String, float[]> embedQueries(
            EmbeddingApi embeddings,
            List<CorpusCase> corpusCases,
            List<GroundingCase> groundingCases
    ) throws Exception {
        Map<String, float[]> vectors = new HashMap<>();
        for (CorpusCase evalCase : corpusCases) {
            if (!vectors.containsKey(evalCase.query())) {
                vectors.put(evalCase.query(), embeddings.embedOne(evalCase.query()));
            }
        }
        for (GroundingCase evalCase : groundingCases) {
            if (!vectors.containsKey(evalCase.query())) {
                vectors.put(evalCase.query(), embeddings.embedOne(evalCase.query()));
            }
        }
        return vectors;
    }

    private static List<RankedSentence> retrievedCorpus(
            Connection connection, CorpusCase evalCase, Map<String, float[]> queryVectors) {
        String sql = """
                SELECT id, word_form, sentence, source, license,
                       1 - (embedding <=> CAST(? AS halfvec)) AS similarity
                FROM corpus_sentence
                WHERE word_form = ?
                    AND embedding IS NOT NULL
                ORDER BY embedding <=> CAST(? AS halfvec), id
                """;
        String vector = vectorLiteral(queryVectors.get(evalCase.query()));
        List<RankedSentence> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, vector);
            statement.setString(2, evalCase.wordForm());
            statement.setString(3, vector);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(new RankedSentence(
                            resultSet.getString("id"),
                            resultSet.getString("word_form"),
                            resultSet.getString("sentence"),
                            resultSet.getDouble("similarity")));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("语料例句评测查询失败", exception);
        }
        return rows;
    }

    private static Metric groundingMetric(
            Connection connection,
            List<GroundingCase> cases,
            Map<String, float[]> queryVectors,
            int perCategoryHits,
            int maxHits,
            double threshold
    ) {
        int hits = 0;
        int expectedHits = 0;
        int returned = 0;
        int expectedReturned = 0;
        for (GroundingCase evalCase : cases) {
            List<RankedMaterial> materials = retrievedGrounding(
                    connection, evalCase, queryVectors, perCategoryHits, maxHits, threshold);
            if (!materials.isEmpty()) {
                hits++;
            }
            boolean expected = materials.stream()
                    .anyMatch(material -> evalCase.expectedContents().stream()
                            .anyMatch(expectedContent -> material.content().contains(expectedContent)));
            if (expected) {
                expectedHits++;
            }
            returned += materials.size();
            expectedReturned += (int) materials.stream()
                    .filter(material -> evalCase.expectedContents().stream()
                            .anyMatch(expectedContent -> material.content().contains(expectedContent)))
                    .count();
        }
        return new Metric(
                cases.isEmpty() ? 0 : (double) hits / cases.size(),
                cases.isEmpty() ? 0 : (double) expectedHits / cases.size(),
                returned == 0 ? 0 : (double) expectedReturned / returned,
                cases.isEmpty() ? 0 : (double) returned / cases.size());
    }

    private static List<RankedMaterial> retrievedGrounding(
            Connection connection,
            GroundingCase evalCase,
            Map<String, float[]> queryVectors,
            int perCategoryHits,
            int maxHits,
            double threshold
    ) {
        String sql = """
                WITH scored AS (
                    SELECT id, category, content, source, license,
                           embedding <=> CAST(? AS halfvec) AS distance
                    FROM grounding_material
                    WHERE category IN (%s)
                        AND embedding IS NOT NULL
                ), ranked AS (
                    SELECT id, category, content, source, license,
                           1 - distance AS similarity,
                           ROW_NUMBER() OVER (
                               PARTITION BY category
                               ORDER BY distance, id
                           ) AS within_category_rank
                    FROM scored
                )
                SELECT id, category, content, source, license, similarity
                FROM ranked
                WHERE within_category_rank <= ?
                    AND similarity >= ?
                ORDER BY similarity DESC, id
                LIMIT ?
                """.formatted(String.join(",", evalCase.categories().stream().map(ignored -> "?").toList()));
        String vector = vectorLiteral(queryVectors.get(evalCase.query()));
        List<RankedMaterial> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, vector);
            for (String category : evalCase.categories()) {
                statement.setString(index++, category);
            }
            statement.setInt(index++, perCategoryHits);
            statement.setDouble(index++, threshold);
            statement.setInt(index, maxHits);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(new RankedMaterial(
                            resultSet.getString("id"),
                            resultSet.getString("category"),
                            resultSet.getString("content"),
                            resultSet.getDouble("similarity")));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("依据材料评测查询失败", exception);
        }
        return rows;
    }

    // ---------------------------------------------------------------- documents

    /**
     * Manually rebuilds User Document vectors. It is intentionally not called from application
     * startup: Mock-era documents stay unindexed until the operator runs this command, and a provider
     * change never silently spends embedding budget.
     */
    private static void rebuildDocuments(Map<String, String> options) throws Exception {
        String model = env("WRITELAB_MODEL_EMBEDDING_NAME", "text-embedding-3-small");
        boolean refresh = options.containsKey("refresh");
        long limit = options.containsKey("limit") ? Long.parseLong(options.get("limit")) : Long.MAX_VALUE;
        int batchSize = Integer.parseInt(
                options.getOrDefault("batch-size", Integer.toString(DEFAULT_BATCH_SIZE)));
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("--batch-size 必须在 1..100 之间");
        }

        EmbeddingApi embeddings = EmbeddingApi.fromEnvironment();
        long processed = 0;
        long embedded = 0;
        try (Connection connection = connection()) {
            List<DocumentRow> documents = readDocumentRows(connection);
            System.out.printf(Locale.ROOT,
                    "documents: %d READY documents, model=%s, refresh=%s%n",
                    documents.size(), model, refresh);
            for (DocumentRow document : documents) {
                if (processed >= limit) {
                    break;
                }
                boolean missing = countUnembeddedChunks(connection, document) > 0;
                boolean staleModel = !model.equals(document.embeddingModel());
                if (!refresh && !missing && !staleModel) {
                    continue;
                }
                processed++;
                boolean reembedAll = refresh || staleModel;
                List<Row> chunks = readDocumentChunks(connection, document, reembedAll);
                System.out.printf(Locale.ROOT, "%s (%s): %d chunks to embed%n",
                        document.filename(), document.id(), chunks.size());
                for (int from = 0; from < chunks.size(); from += batchSize) {
                    List<Row> batch = List.copyOf(chunks.subList(from, Math.min(chunks.size(), from + batchSize)));
                    List<String> texts = batch.stream().map(Row::text).toList();
                    updateRows(connection, Table.documentChunk(), batch, embeddings.embed(texts));
                    embedded += batch.size();
                }
                long remaining = countUnembeddedChunks(connection, document);
                if (remaining > 0) {
                    throw new IllegalStateException(document.filename() + " 仍有 " + remaining + " 个分块没有向量");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE user_document
                        SET embedding_model = ?, updated_at = now()
                        WHERE id = ?
                        """)) {
                    statement.setString(1, model);
                    statement.setObject(2, java.util.UUID.fromString(document.id()));
                    statement.executeUpdate();
                }
            }
        }
        System.out.printf(Locale.ROOT,
                "document vector rebuild finished: %d documents, %d chunks written (model=%s)%n",
                processed, embedded, model);
    }

    private static List<DocumentRow> readDocumentRows(Connection connection) throws SQLException {
        List<DocumentRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id, filename, active_generation, embedding_model
                        FROM user_document
                        WHERE status = 'READY'
                        ORDER BY created_at, id
                        """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                rows.add(new DocumentRow(
                        resultSet.getString("id"),
                        resultSet.getString("filename"),
                        resultSet.getInt("active_generation"),
                        resultSet.getString("embedding_model")));
            }
        }
        return rows;
    }

    private static long countUnembeddedChunks(Connection connection, DocumentRow document) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT count(*) FROM document_chunk
                        WHERE document_id = ? AND generation = ? AND embedding IS NULL
                        """)) {
            statement.setObject(1, java.util.UUID.fromString(document.id()));
            statement.setInt(2, document.generation());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static List<Row> readDocumentChunks(
            Connection connection, DocumentRow document, boolean reembedAll) throws SQLException {
        String sql = "SELECT id, content FROM document_chunk WHERE document_id = ? AND generation = ? "
                + (reembedAll ? "" : "AND embedding IS NULL ")
                + "ORDER BY position";
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, java.util.UUID.fromString(document.id()));
            statement.setInt(2, document.generation());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(new Row(resultSet.getString("id"), resultSet.getString("content")));
                }
            }
        }
        return rows;
    }

    // ---------------------------------------------------------------- eval-documents

    /**
     * Offline User Document fixture evaluation. It uses the same relevance rules as the REAL
     * retriever (cosine threshold, top-4, at most two chunks per document, identical text
     * deduplicated) but keeps the fixture corpus in memory, so a live pgvector database is not
     * required. It is a manual tool and is never invoked by Maven, the frontend build or CI.
     */
    private static void evaluateDocuments(Map<String, String> options) throws Exception {
        Path caseDirectory = Path.of(options.getOrDefault("cases", "tools/retrieval-eval"));
        List<DocumentFixture> fixtures = readDocumentFixtures(
                caseDirectory.resolve("document_fixtures.csv"));
        List<DocumentCase> cases = readDocumentCases(
                caseDirectory.resolve("document_cases.csv"));
        double threshold = options.containsKey("threshold")
                ? Double.parseDouble(options.get("threshold"))
                : doubleEnv("WRITELAB_USER_DOCUMENT_SIMILARITY_THRESHOLD", 0.55);
        int maxHits = options.containsKey("max-hits")
                ? Integer.parseInt(options.get("max-hits"))
                : intEnv("WRITELAB_USER_DOCUMENT_MAX_HITS", 4);
        int perDocument = options.containsKey("per-document")
                ? Integer.parseInt(options.get("per-document"))
                : intEnv("WRITELAB_USER_DOCUMENT_PER_DOCUMENT_HITS", 2);

        EmbeddingApi embeddings = EmbeddingApi.fromEnvironment();
        LinkedHashSet<String> texts = new LinkedHashSet<>();
        fixtures.forEach(fixture -> texts.add(fixture.content()));
        cases.forEach(evalCase -> texts.add(evalCase.query()));
        Map<String, float[]> vectors = embedTexts(embeddings, new ArrayList<>(texts), DEFAULT_BATCH_SIZE);

        System.out.printf(Locale.ROOT,
                "documents-eval: cases=%d fixtures=%d threshold=%.2f max-hits=%d per-document=%d%n",
                cases.size(), fixtures.size(), threshold, maxHits, perDocument);
        int passed = 0;
        for (DocumentCase evalCase : cases) {
            List<RankedDocument> ranked = retrieveDocuments(
                    fixtures, vectors, vectors.get(evalCase.query()), threshold, maxHits, perDocument);
            List<String> keys = ranked.stream().map(RankedDocument::key).toList();
            boolean expected = keys.containsAll(evalCase.expectedKeys());
            boolean noDistractor = evalCase.distractorKeys().stream().noneMatch(keys::contains);
            boolean none = !evalCase.expectNone() || ranked.isEmpty();
            boolean ok = expected && noDistractor && none;
            if (ok) {
                passed++;
            }
            System.out.printf(Locale.ROOT, "  %-28s %s expected=%s distractor=%s top=%s%n",
                    evalCase.name(),
                    ok ? "PASS" : "FAIL",
                    expected ? "hit" : "MISS",
                    noDistractor ? "clean" : "LEAK",
                    keys.isEmpty() ? "<none>" : String.join(" | ", keys));
        }
        System.out.printf(Locale.ROOT, "documents-eval result: %d/%d cases passed%n", passed, cases.size());
        if (passed != cases.size()) {
            throw new IllegalStateException("用户文档 fixture 评测未全部通过");
        }
    }

    private static List<RankedDocument> retrieveDocuments(
            List<DocumentFixture> fixtures,
            Map<String, float[]> vectors,
            float[] queryVector,
            double threshold,
            int maxHits,
            int perDocument
    ) {
        Map<String, Candidate> deduplicated = new LinkedHashMap<>();
        Map<String, Integer> positions = new HashMap<>();
        for (DocumentFixture fixture : fixtures) {
            int position = positions.merge(fixture.filename(), 1, Integer::sum) - 1;
            double similarity = cosine(queryVector, vectors.get(fixture.content()));
            if (similarity < threshold) {
                continue;
            }
            Candidate candidate = new Candidate(
                    fixture.key(), fixture.filename(), fixture.headingPath(),
                    fixture.content(), position, similarity);
            Candidate existing = deduplicated.get(fixture.content());
            if (existing == null || candidate.betterThan(existing)) {
                deduplicated.put(fixture.content(), candidate);
            }
        }

        List<Candidate> perDocumentFiltered = new ArrayList<>();
        deduplicated.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        Candidate::filename, LinkedHashMap::new, java.util.stream.Collectors.toList()))
                .forEach((filename, candidates) -> {
                    candidates.sort(Candidate.ORDER);
                    perDocumentFiltered.addAll(candidates.subList(0, Math.min(perDocument, candidates.size())));
                });
        return perDocumentFiltered.stream()
                .sorted(Candidate.ORDER)
                .limit(maxHits)
                .map(candidate -> new RankedDocument(
                        candidate.key(), candidate.filename(), candidate.headingPath(),
                        candidate.similarity()))
                .toList();
    }

    private static Map<String, float[]> embedTexts(
            EmbeddingApi embeddings, List<String> texts, int batchSize) throws Exception {
        Map<String, float[]> vectors = new HashMap<>();
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> batch = texts.subList(from, Math.min(texts.size(), from + batchSize));
            List<float[]> embedded = embeddings.embed(batch);
            for (int index = 0; index < batch.size(); index++) {
                vectors.put(batch.get(index), embedded.get(index));
            }
        }
        return vectors;
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += (double) left[index] * right[index];
            leftNorm += (double) left[index] * left[index];
            rightNorm += (double) right[index] * right[index];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    // ---------------------------------------------------------------- files

    private static List<CorpusCase> readCorpusCases(Path path) throws IOException {
        List<CorpusCase> cases = new ArrayList<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int lineNumber = 2; lineNumber <= lines.size(); lineNumber++) {
            String line = lines.get(lineNumber - 1);
            if (line.isBlank()) {
                continue;
            }
            List<String> fields = csvFields(line);
            if (fields.size() != 3) {
                throw new IllegalStateException(path + " 第 " + lineNumber + " 行有 " + fields.size() + " 列");
            }
            cases.add(new CorpusCase(
                    fields.get(0).strip().toLowerCase(Locale.ROOT),
                    fields.get(1),
                    splitExpected(fields.get(2))));
        }
        return cases;
    }

    private static List<GroundingCase> readGroundingCases(Path path) throws IOException {
        List<GroundingCase> cases = new ArrayList<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int lineNumber = 2; lineNumber <= lines.size(); lineNumber++) {
            String line = lines.get(lineNumber - 1);
            if (line.isBlank()) {
                continue;
            }
            List<String> fields = csvFields(line);
            if (fields.size() != 3) {
                throw new IllegalStateException(path + " 第 " + lineNumber + " 行有 " + fields.size() + " 列");
            }
            List<String> categories = Arrays.stream(fields.get(1).split("\\|"))
                    .map(String::strip)
                    .map(value -> value.toUpperCase(Locale.ROOT))
                    .toList();
            for (String category : categories) {
                if (!CATEGORIES.contains(category)) {
                    throw new IllegalStateException(path + " 第 " + lineNumber + " 行类别未知: " + category);
                }
            }
            cases.add(new GroundingCase(fields.get(0), categories, splitExpected(fields.get(2))));
        }
        return cases;
    }

    private static List<DocumentFixture> readDocumentFixtures(Path path) throws IOException {
        List<DocumentFixture> fixtures = new ArrayList<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int lineNumber = 2; lineNumber <= lines.size(); lineNumber++) {
            String line = lines.get(lineNumber - 1);
            if (line.isBlank()) {
                continue;
            }
            List<String> fields = csvFields(line);
            if (fields.size() != 4) {
                throw new IllegalStateException(path + " 第 " + lineNumber + " 行有 " + fields.size() + " 列");
            }
            fixtures.add(new DocumentFixture(
                    fields.get(0).strip(),
                    fields.get(1).strip(),
                    fields.get(2).strip(),
                    unescape(fields.get(3))));
        }
        return fixtures;
    }

    private static List<DocumentCase> readDocumentCases(Path path) throws IOException {
        List<DocumentCase> cases = new ArrayList<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int lineNumber = 2; lineNumber <= lines.size(); lineNumber++) {
            String line = lines.get(lineNumber - 1);
            if (line.isBlank()) {
                continue;
            }
            List<String> fields = csvFields(line);
            if (fields.size() != 5) {
                throw new IllegalStateException(path + " 第 " + lineNumber + " 行有 " + fields.size() + " 列");
            }
            cases.add(new DocumentCase(
                    fields.get(0).strip(),
                    unescape(fields.get(1).strip()),
                    splitKeys(fields.get(2)),
                    splitKeys(fields.get(3)),
                    Boolean.parseBoolean(fields.get(4).strip())));
        }
        return cases;
    }

    private static List<String> splitKeys(String value) {
        return Arrays.stream(value.split("\\|"))
                .map(String::strip)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    private static String unescape(String value) {
        return value.replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r");
    }

    private static List<String> splitExpected(String value) {
        return Arrays.stream(value.split("\\|\\|"))
                .map(String::strip)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    private static List<String> csvFields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (quoted) {
                if (character == '"' && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else if (character == '"') {
                    quoted = false;
                } else {
                    current.append(character);
                }
            } else if (character == '"') {
                quoted = true;
            } else if (character == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    // ---------------------------------------------------------------- helpers

    private static int expectedRank(List<RankedSentence> rankings, List<String> expectedSentences) {
        for (int index = 0; index < rankings.size(); index++) {
            String sentence = rankings.get(index).sentence();
            if (expectedSentences.stream().anyMatch(expected -> sentence.equalsIgnoreCase(expected))) {
                return index + 1;
            }
        }
        return 0;
    }

    private static double rate(Collection<CorpusCase> cases, java.util.function.Predicate<CorpusCase> predicate) {
        if (cases.isEmpty()) {
            return 0;
        }
        return cases.stream().filter(predicate).count() / (double) cases.size();
    }

    private static boolean containsWordForm(String sentence, String wordForm) {
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(wordForm) + "\\b", Pattern.CASE_INSENSITIVE);
        return pattern.matcher(sentence).find();
    }

    private static String vectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder(vector.length * 8).append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(vector[index]);
        }
        return literal.append(']').toString();
    }

    private static Connection connection() throws SQLException {
        String url = env("SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/writelab");
        String username = env("SPRING_DATASOURCE_USERNAME", "writelab");
        String password = env("SPRING_DATASOURCE_PASSWORD", "writelab");
        return DriverManager.getConnection(url, username, password);
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static String human(long bytes) {
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static double doubleEnv(String name, double fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Double.parseDouble(value);
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("未知参数: " + argument);
            }
            String key = argument.substring(2);
            if (index + 1 < args.length && !args[index + 1].startsWith("--")) {
                options.put(key, args[++index]);
            } else {
                options.put(key, "true");
            }
        }
        return options;
    }

    private static List<Double> doubles(String csv) {
        return Arrays.stream(csv.split(",")).map(String::strip).filter(value -> !value.isEmpty())
                .map(Double::parseDouble).toList();
    }

    private static List<Integer> integers(String csv) {
        return Arrays.stream(csv.split(",")).map(String::strip).filter(value -> !value.isEmpty())
                .map(Integer::parseInt).toList();
    }

    private record Table(String name, String textColumn) {
        static Table corpus() {
            return new Table("corpus_sentence", "sentence");
        }

        static Table grounding() {
            return new Table("grounding_material", "content");
        }

        static Table documentChunk() {
            return new Table("document_chunk", "content");
        }

        String embeddingText(Row row) {
            return row.text();
        }
    }

    private record DocumentRow(
            String id,
            String filename,
            int generation,
            String embeddingModel
    ) {
    }

    private record DocumentFixture(String key, String filename, String headingPath, String content) {
    }

    private record DocumentCase(
            String name,
            String query,
            List<String> expectedKeys,
            List<String> distractorKeys,
            boolean expectNone
    ) {
    }

    private record Candidate(
            String key,
            String filename,
            String headingPath,
            String content,
            int position,
            double similarity
    ) {

        private static final java.util.Comparator<Candidate> ORDER =
                java.util.Comparator.comparingDouble(Candidate::similarity).reversed()
                        .thenComparing(Candidate::filename)
                        .thenComparingInt(Candidate::position);

        private boolean betterThan(Candidate other) {
            return ORDER.compare(this, other) < 0;
        }
    }

    private record RankedDocument(String key, String filename, String headingPath, double similarity) {
    }

    private record Row(String id, String text) {
    }

    private record BatchResult(List<Row> rows, List<float[]> vectors) {
    }

    private record CorpusCase(String wordForm, String query, List<String> expectedSentences) {
    }

    private record GroundingCase(String query, List<String> categories, List<String> expectedContents) {
    }

    private record RankedSentence(String id, String wordForm, String sentence, double similarity) {
    }

    private record RankedMaterial(String id, String category, String content, double similarity) {
    }

    private record Metric(double hitRate, double expectedHitRate, double expectedPrecision,
                          double averageMaterials) {
    }

    private record Batch(List<Row> rows, List<float[]> vectors) {
    }

    // ---------------------------------------------------------------- embedding provider

    private static final class EmbeddingApi {

        private final HttpClient httpClient;
        private final URI endpoint;
        private final String apiKey;
        private final String model;

        private EmbeddingApi(HttpClient httpClient, URI endpoint, String apiKey, String model) {
            this.httpClient = httpClient;
            this.endpoint = endpoint;
            this.apiKey = apiKey;
            this.model = model;
        }

        static EmbeddingApi fromEnvironment() {
            String baseUrl = env("WRITELAB_MODEL_BASE_URL", "https://api.openai.com").replaceAll("/+$", "");
            String explicitUrl = System.getenv("WRITELAB_MODEL_EMBEDDING_URL");
            URI endpoint = URI.create(explicitUrl != null && !explicitUrl.isBlank()
                    ? explicitUrl
                    : (baseUrl.endsWith("/v1") ? baseUrl + "/embeddings" : baseUrl + "/v1/embeddings"));
            String apiKey = System.getenv("WRITELAB_MODEL_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("WRITELAB_MODEL_API_KEY 为空，无法调用 embedding 服务");
            }
            String model = env("WRITELAB_MODEL_EMBEDDING_NAME", "text-embedding-3-small");
            return new EmbeddingApi(
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(30))
                            .build(),
                    endpoint, apiKey, model);
        }

        String model() {
            return model;
        }

        float[] embedOne(String text) throws Exception {
            return embed(List.of(text)).getFirst();
        }

        List<float[]> embed(List<String> texts) throws Exception {
            String body = "{\"model\":" + jsonString(model)
                    + ",\"input\":[" + String.join(",", texts.stream().map(RetrievalTooling::jsonString).toList())
                    + "],\"dimensions\":" + DIMENSIONS + "}";
            Exception last = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    HttpRequest request = HttpRequest.newBuilder(endpoint)
                            .timeout(Duration.ofSeconds(120))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build();
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    int status = response.statusCode();
                    if (status >= 200 && status < 300) {
                        return parse(response.body(), texts.size());
                    }
                    if (status == 429 || status >= 500) {
                        throw new IOException("embedding provider returned HTTP " + status + ": "
                                + abbreviate(response.body()));
                    }
                    throw new IllegalStateException("embedding provider returned HTTP " + status + ": "
                            + abbreviate(response.body()));
                } catch (IOException exception) {
                    last = exception;
                    if (attempt == MAX_ATTEMPTS) {
                        break;
                    }
                    Thread.sleep(Math.min(60_000L, 1_000L * (1L << (attempt - 1))));
                }
            }
            throw new IllegalStateException("embedding 调用失败: " + last.getMessage(), last);
        }

        private static List<float[]> parse(String body, int expectedCount) {
            Matcher matcher = EMBEDDING_ARRAY.matcher(body);
            List<float[]> vectors = new ArrayList<>(expectedCount);
            while (matcher.find()) {
                String[] values = matcher.group(1).split(",");
                float[] vector = new float[values.length];
                for (int index = 0; index < values.length; index++) {
                    vector[index] = Float.parseFloat(values[index].strip());
                }
                vectors.add(vector);
            }
            if (vectors.size() != expectedCount) {
                throw new IllegalStateException("embedding 返回 " + vectors.size() + " 个向量，期望 " + expectedCount);
            }
            for (float[] vector : vectors) {
                if (vector.length != DIMENSIONS) {
                    throw new IllegalStateException("embedding 返回的向量维度不是 " + DIMENSIONS);
                }
            }
            return vectors;
        }
    }

    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static String abbreviate(String value) {
        String singleLine = value == null ? "" : value.replaceAll("\\s+", " ");
        return singleLine.length() <= 240 ? singleLine : singleLine.substring(0, 240) + "...";
    }
}
