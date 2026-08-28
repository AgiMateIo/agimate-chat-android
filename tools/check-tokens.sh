#!/usr/bin/env bash
# Сверяет вендоренный AgimateTokens.kt с исходником в репозитории айдентики.
#
# Файл генерируется там командой `pnpm tokens` и копируется сюда — это осознанный размен: копия
# видна в диффе и не может сломать мобильную сборку в неудачный момент, но означает, что смена
# токена это правка в трёх репозиториях. Проверка ловит третью, про которую забыли.
#
# Копируется он не совсем как есть: подменяется одна строка, `package`. Айдентика — общий источник
# для нескольких платформ и живёт в своём неймспейсе, а приложению незачем держать второй корень
# пакетов ради одного файла. Подмена механическая, руками файл по-прежнему не правят, и сравнение
# ниже строку `package` игнорирует — разойтись она не может.
#
# В CI этой проверки нет и быть не может: айдентики там нет, и скрипт вышел бы с нулём, ничего не
# проверив. Запускать локально перед правкой темы.
#
# Путь к айдентике — переменная AGIMATE_IDENTICA, по умолчанию соседний каталог.
set -euo pipefail

package="ru.agimate.mobile.design"
vendored="app/src/main/java/ru/agimate/mobile/design/AgimateTokens.kt"
identica="${AGIMATE_IDENTICA:-../identica}"
source_file="$identica/v1/design/dist/AgimateTokens.kt"

if [ ! -f "$source_file" ]; then
    echo "check-tokens: исходник не найден: $source_file"
    echo "check-tokens: пропускаю — задайте AGIMATE_IDENTICA, если айдентика лежит в другом месте"
    exit 0
fi

# Строка package у копии обязана быть нашей: иначе файл скопировали мимо подмены.
if ! grep -qx "package $package" "$vendored"; then
    echo "check-tokens: у $vendored не тот package — ожидается «package ${package}»"
    echo "check-tokens: скопируйте заново командой ниже"
    echo "    sed 's|^package .*|package $package|' $source_file > $vendored"
    exit 1
fi

if diff -u <(grep -v '^package ' "$vendored") <(grep -v '^package ' "$source_file") > /dev/null; then
    echo "check-tokens: токены совпадают"
else
    echo "check-tokens: $vendored разошёлся с айдентикой"
    diff -u <(grep -v '^package ' "$vendored") <(grep -v '^package ' "$source_file") || true
    echo
    echo "check-tokens: скопируйте исходник заново — вендоренный файл руками не правят:"
    echo "    sed 's|^package .*|package $package|' $source_file > $vendored"
    exit 1
fi
