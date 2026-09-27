#!/bin/sh
set -eu

src=/workspace/src
auth=$(printf 'x-access-token:%s' "$LIFTGATE_GIT_TOKEN" | base64 | tr -d '\n')

git init -q "$src"
git -C "$src" -c "http.extraHeader=Authorization: Basic $auth" fetch -q --depth 1 "$LIFTGATE_REPO_URL" "$LIFTGATE_COMMIT"
git -C "$src" checkout -q FETCH_HEAD
