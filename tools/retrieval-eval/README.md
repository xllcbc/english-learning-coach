# Retrieval evaluation cases

This directory holds the small, hand-labelled cases used by the offline retrieval evaluation. It
is **not** a test fixture and it is not wired into Maven or CI.

- `corpus_cases.csv` pairs a word form and a learner sentence with one or more manually chosen
  acceptable Corpus Sentences. The script reports whether any labelled sentence appears at rank 1
  or rank 3, plus the retrieval rate and the share of adopted examples that contain the word form.
- `grounding_cases.csv` pairs a focused-issue query and its categories with one or more manually
  chosen Grounding Material contents. The script sweeps the similarity threshold and top-k and
  reports hit rate, expected-material hit rate, category precision, and average materials per
  query.

The cases are deliberately small: they are a regression signal for the bundled datasets, not a
statistically representative benchmark. Annotate more cases when the corpus or the IssueCategory
taxonomy changes.

Run from the repository root with a live database whose embeddings have already been built:

```bash
tools/retrieval-tooling.sh eval
```

See [`docs/retrieval-evaluation.md`](../../docs/retrieval-evaluation.md) for the full manual
procedure and the latest recorded results.
