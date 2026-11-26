#!/usr/bin/env bash
# On-demand evaluation of the four agent platform gates. Manual entry point only: the evaluations
# are tagged `evaluation` and are excluded from the default Maven test suite and CI.
#
#   tools/agent-platform-eval.sh all
#   tools/agent-platform-eval.sh teacher-conversation
#   tools/agent-platform-eval.sh learning-review
#   tools/agent-platform-eval.sh targeted-practice
#   tools/agent-platform-eval.sh speaking-practice
#
# Each run prints a Markdown report, writes it to backend/target/agent-eval/<gate>.md, and exits
# non-zero when any threshold fails.
set -uo pipefail

cd "$(dirname "$0")/.."

usage() {
  echo "用法: tools/agent-platform-eval.sh [all|teacher-conversation|learning-review|targeted-practice|speaking-practice]" >&2
}

gate="${1:-all}"
case "$gate" in
  all) selector='*GateEvaluation' ;;
  teacher-conversation) selector='TeacherConversationGateEvaluation' ;;
  learning-review) selector='LearningReviewGateEvaluation' ;;
  targeted-practice) selector='TargetedPracticeGateEvaluation' ;;
  speaking-practice) selector='SpeakingPracticeGateEvaluation' ;;
  -h|--help|help) usage; exit 0 ;;
  *) usage; exit 2 ;;
esac

repo_args=()
for candidate in \
  "${WRITELAB_MAVEN_REPO_LOCAL:-}" \
  "$HOME/.m2/repository" \
  "$HOME/.cache/sandcastle/m2" \
  /home/*/.m2/repository \
  /home/*/.cache/sandcastle/m2 \
  /root/.m2/repository; do
  [ -n "$candidate" ] || continue
  if [ -f "$candidate/org/springframework/boot/spring-boot-starter-parent/4.1.2/spring-boot-starter-parent-4.1.2.pom" ]; then
    repo_args=("-Dmaven.repo.local=$candidate")
    break
  fi
done

cd backend
if [ "$gate" = "all" ]; then
  rm -rf target/agent-eval
else
  rm -f "target/agent-eval/${gate}.md"
fi

status=0
env -u WRITELAB_MODEL_API_KEY -u WRITELAB_MODEL_BASE_URL -u WRITELAB_MODEL_NAME \
    -u WRITELAB_MODEL_EMBEDDING_NAME \
  mvn -q -Pagent-eval -Dtest="$selector" "${repo_args[@]}" test || status=$?

if [ "$gate" = "all" ]; then
  reports=(target/agent-eval/*.md)
else
  reports=("target/agent-eval/${gate}.md")
fi
for report in "${reports[@]}"; do
  if [ -f "$report" ]; then
    cat "$report"
    printf '\n'
  fi
done

if [ "$status" -ne 0 ]; then
  echo "评测失败（gate: $gate，退出码 $status）" >&2
fi
exit "$status"
