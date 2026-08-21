#!/usr/bin/env bash
# Сверяет вендоренный AgimateTokens.kt с исходником в репозитории айдентики.
#
# Файл генерируется там командой `pnpm tokens` и копируется сюда как есть — это осознанный размен:
# копия видна в диффе и не может сломать мобильную сборку в неудачный момент, но означает, что
# смена токена это правка в трёх репозиториях. Проверка ловит третью, про которую забыли.
#
# Путь к айдентике — переменная AGIMATE_IDENTICA, по умолчанию соседний каталог.
set -euo pipefail

vendored="app/src/main/java/com/agimate/design/AgimateTokens.kt"
identica="${AGIMATE_IDENTICA:-../identica}"
source_file="$identica/v1/design/dist/AgimateTokens.kt"

if [ ! -f "$source_file" ]; then
    echo "check-tokens: исходник не найден: $source_file"
    echo "check-tokens: пропускаю — задайте AGIMATE_IDENTICA, если айдентика лежит в другом месте"
    exit 0
fi

if diff -u "$vendored" "$source_file" > /dev/null; then
    echo "check-tokens: токены совпадают"
else
    echo "check-tokens: $vendored разошёлся с айдентикой"
    diff -u "$vendored" "$source_file" || true
    echo
    echo "check-tokens: скопируйте исходник заново — вендоренный файл руками не правят:"
    echo "    cp $source_file $vendored"
    exit 1
fi
