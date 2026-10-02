#!/usr/bin/env bash
# Blocks AI edits to the public contract package (see README "Reglas del contrato").
path=$(jq -r '.tool_input.file_path // empty')
if [[ "$path" == *"/com/store/inventory/api/"* ]]; then
  echo "Blocked: $path is part of the public contract and must not be modified." >&2
  exit 2
fi
