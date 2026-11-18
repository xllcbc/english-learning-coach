#!/usr/bin/env bash
# 检查一条分支上的红绿序列。
# 用法：bash tools/check-redgreen.sh [base] [target]
#   base   对比基线，默认 origin/master
#   target 被检查的分支，默认 HEAD（在沙箱 worktree 里跑时就是 agent 分支）
set -euo pipefail

base="${1:-origin/master}"
target="${2:-HEAD}"
fail=0
count=0

for sha in $(git rev-list --reverse "$base".."$target"); do
  subject=$(git log -1 --format=%s "$sha")
  changed=$(git show --name-only --format= "$sha")

  has_test=$(printf '%s\n' "$changed" | grep -cE '(^|/)src/test/' || true)
  has_main=$(printf '%s\n' "$changed" | grep -cE '(^|/)src/main/' || true)

  case "$subject" in
    red:*)
      [ "$has_main" -eq 0 ] || { echo "x $sha 是 red 提交却改了 src/main/"; fail=1; }
      [ "$has_test" -gt 0 ] || { echo "x $sha 是 red 提交却没有改 src/test/"; fail=1; } ;;
    green:*)
      [ "$has_test" -eq 0 ] || { echo "x $sha 是 green 提交却改了 src/test/"; fail=1; }
      [ "$has_main" -gt 0 ] || { echo "x $sha 是 green 提交却没有改 src/main/"; fail=1; } ;;
    *)
      echo "! $sha 没有 red/green 前缀：$subject" ;;
  esac
  count=$((count + 1))
done

[ "$count" -ge 2 ] || { echo "x 提交数不足（红绿至少两次），实际 $count"; fail=1; }

if ! git ls-tree -r --name-only "$target" -- docs/red | grep -qE '^docs/red/issue-.*\.md$'; then
  echo "x 没有任何 docs/red 记录"
  exit 1
fi

exit "$fail"
