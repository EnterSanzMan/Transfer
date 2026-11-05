#!/usr/bin/env bash
# Script de arranque para Transfer en Linux
set -e

# Configuración por variables de entorno o valores por defecto
PORT="${PORT:-8080}"
DB_PATH="${DB_PATH:-./data/transfer.db}"
UPLOAD_DIR="${UPLOAD_DIR:-./uploads}"

# Asegurar directorios
mkdir -p "$(dirname "$DB_PATH")"
mkdir -p "$UPLOAD_DIR"

echo "==========================================="
echo "   Iniciando Transfer en Linux"
echo "   Puerto:      $PORT"
echo "   BBDD SQLite: $DB_PATH"
echo "   Subidas:     $UPLOAD_DIR"
echo "==========================================="

exec java -jar target/transfer-1.0.0.jar \
    --server.port="$PORT" \
    --spring.datasource.url="jdbc:sqlite:$DB_PATH" \
    --transfer.upload-dir="$UPLOAD_DIR"
