#!/usr/bin/env bash
set -euo pipefail

ref=${1:?pass a Git ref}
sha=${2:?pass a commit SHA}
case "$ref" in
  refs/heads/main)
    printf 'parsa202089/torchnode:edge\nparsa202089/torchnode:sha-%s\n' "${sha:0:12}"
    ;;
  refs/tags/v*)
    version=${ref#refs/tags/v}
    if [[ ! $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
      echo 'Only stable vX.Y.Z release tags can publish.' >&2
      exit 2
    fi
    IFS=. read -r major minor patch <<< "$version"
    printf 'parsa202089/torchnode:%s\nparsa202089/torchnode:%s.%s\nparsa202089/torchnode:%s\nparsa202089/torchnode:latest\n' \
      "$version" "$major" "$minor" "$major"
    ;;
  *)
    echo 'This ref does not publish an image.' >&2
    exit 2
    ;;
esac
