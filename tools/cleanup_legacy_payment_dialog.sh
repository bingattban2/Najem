#!/usr/bin/env bash
# Najim WiFi legacy-source cleanup. Run from the Najimwifi project directory.
set -euo pipefail
rel="app/src/main/java/com/najimwifi/app/ui/home/PaymentDialog.kt"
if [ ! -f "settings.gradle.kts" ] || [ ! -d "app/src/main/java/com/najimwifi/app" ]; then
  echo "خطأ: شغّل الملف من مجلد مشروع Najimwifi." >&2
  exit 2
fi
if [ ! -f "$rel" ]; then
  echo "ملف PaymentDialog.kt القديم غير موجود؛ لا يوجد ما يجب حذفه."
  exit 0
fi
# Check for external references; don't delete a live screen.
ref=$(grep -R -n --include='*.kt' 'PaymentDialog' app/src/main/java 2>/dev/null | grep -v "^$rel:" || true)
if [ -n "$ref" ]; then
  echo "تم إيقاف التنظيف: توجد استدعاءات لواجهة PaymentDialog خارج ملفها:" >&2
  printf '%s\n' "$ref" >&2
  exit 3
fi
mkdir -p .cleanup-backups
if [ -e .cleanup-backups/PaymentDialog.kt.bak ]; then
  echo "نسخة احتياطية سابقة موجودة؛ لن أستبدلها." >&2
  exit 4
fi
mv -- "$rel" .cleanup-backups/PaymentDialog.kt.bak
echo "نجح: نقل الواجهة القديمة خارج مجلد Kotlin. لم تتغير البيانات أو الوظائف الحالية."
echo "اختبر البناء: bash ./gradlew -Pandroid.aapt2FromMavenOverride=\"\$HOME/android-sdk/build-tools/36.0.0/aapt2\" :app:assembleDebug :app:testDebugUnitTest"
