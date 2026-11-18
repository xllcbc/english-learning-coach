#!/usr/bin/env bash
# 检查一条分支上的红绿序列。
# 用法：bash tools/check-redgreen.sh [base] [target]
#   base   对比基线，默认 master
#   target 被检查的分支，默认 HEAD（在沙箱 worktree 里跑时就是 agent 分支）
#
# 四种提交：
#   red:    只允许改 src/test/，必须成对出现的第一半
#   green:  只允许改 src/main/（文档可并入），必须成对出现的第二半
#   docs:   只允许改 docs/、tools/ 或 markdown；不参与红绿配对
#   config: 只允许改运行时配置（yml/yaml/properties/toml/json）；不碰 Java 代码与测试
set -euo pipefail

base="${1:-master}"
target="${2:-HEAD}"
fail=0
rg_count=0
code_count=0
expected="red"

for sha in $(git rev-list --reverse "$base".."$target"); do
  subject=$(git log -1 --format=%s "$sha")
  changed=$(git show --name-only --format= "$sha")

  has_test=$(printf '%s\n' "$changed" | grep -cE '(^|/)src/test/' || true)
  has_main=$(printf '%s\n' "$changed" | grep -cE '(^|/)src/main/' || true)
  has_java_main=$(printf '%s\n' "$changed" | grep -cE '(^|/)src/main/java/' || true)
  has_docs=$(printf '%s\n' "$changed" | grep -cE '(^|/)(docs|tools)/|\.md$' || true)
  has_config=$(printf '%s\n' "$changed" | grep -cE '(^|/)(application|bootstrap)[^/]*\.(ya?ml|properties)$|(^|/)src/main/resources/.*\.(ya?ml|properties|json|toml)$|\.(ya?ml|properties|toml)$' || true)

  if [ "$has_test" -gt 0 ] || [ "$has_java_main" -gt 0 ]; then
    code_count=$((code_count + 1))
  fi

  case "$subject" in
    red:*)
      [ "$expected" = "red" ] || { echo "x $sha 应该是 green 却出现了 red"; fail=1; }
      [ "$has_main" -eq 0 ] || { echo "x $sha 是 red 提交却改了 src/main/"; fail=1; }
      [ "$has_test" -gt 0 ] || { echo "x $sha 是 red 提交却没有改 src/test/"; fail=1; }
      expected="green"
      rg_count=$((rg_count + 1)) ;;
    green:*)
      [ "$expected" = "green" ] || { echo "x $sha 应该是 red 却出现了 green"; fail=1; }
      [ "$has_test" -eq 0 ] || { echo "x $sha 是 green 提交却改了 src/test/"; fail=1; }
      [ "$has_java_main" -gt 0 ] || { echo "x $sha 是 green 提交却没有改 src/main/java/"; fail=1; }
      expected="red"
      rg_count=$((rg_count + 1)) ;;
    docs:*)
      [ "$has_main" -eq 0 ] || { echo "x $sha 是 docs 提交却改了 src/main/"; fail=1; }
      [ "$has_test" -eq 0 ] || { echo "x $sha 是 docs 提交却改了 src/test/"; fail=1; }
      [ "$has_docs" -gt 0 ] || { echo "x $sha 是 docs 提交却没有改 docs/、tools/ 或 markdown"; fail=1; } ;;
    config:*)
      [ "$has_java_main" -eq 0 ] || { echo "x $sha 是 config 提交却改了 src/main/java/"; fail=1; }
      [ "$has_test" -eq 0 ] || { echo "x $sha 是 config 提交却改了 src/test/"; fail=1; }
      [ "$has_config" -gt 0 ] || { echo "x $sha 是 config 提交却没有改配置文件"; fail=1; } ;;
    *)
      echo "! $sha 没有 red/green/docs/config 前缀：$subject"
      fail=1 ;;
  esac
done

if [ "$code_count" -gt 0 ]; then
  [ "$rg_count" -ge 2 ] || { echo "x 红绿提交不足两次，实际 $rg_count"; fail=1; }
  [ "$expected" = "red" ] || { echo "x 红绿提交没有成对（最后缺少 green）"; fail=1; }

  if ! git ls-tree -r --name-only "$target" -- docs/red | grep -qE '^docs/red/issue-.*\.md$'; then
    echo "x 没有任何 docs/red 记录"
    exit 1
  fi
fi

exit "$fail"
