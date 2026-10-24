# نجم واي فاي — دليل البناء والإصدار

## حالة الاعتماد
هذا الدليل إجراء تسليم، **وليس إثبات نجاح إصدار إنتاجي**. نجاح بناء APK على Code On The Go لا يعادل اختبار تسجيل الدخول، قفل الشاشة، المحاسبة، الاستعادة أو PDF. لا تنشر نسخة Production قبل اكتمال بوابة القبول.

## هوية المشروع
- Android application ID: `com.najimwifi.app` (لا تغيّره عند تحديث تطبيق مثبّت).
- `minSdk=30`، `targetSdk=36`، Java 17.
- احتفظ بـ`versionCode` أكبر من أي إصدار نشرته سابقًا؛ لا تُثبت نسخة أقدم فوق بيانات حقيقية.
- لا تعِد تسمية الملف التنفيذي أو تعدّل بيانات Room عند إنتاج APK.
- Cairo وTajawal جزء من أصول التطبيق؛ احتفظ بتراخيص OFL ضمن assets.

## المتطلبات
- Android SDK Platform 36 + Build Tools 36.0.0.
- Java 17 وGradle Wrapper الأصلي (`gradlew` و`gradle/wrapper/gradle-wrapper.jar`).
- اتصال بالشبكة فقط لتنزيل اعتمادات البناء؛ الوظائف الأساسية للتطبيق تعمل بلا إنترنت.
- احرص على عدم مسح بيانات التطبيق أثناء عملية التحديث؛ عند تغيير مفتاح توقيع APK قد يرفض Android التحديث.

## الأوامر من جذر المشروع
```bash
chmod +x ./gradlew
./gradlew --no-daemon :app:clean :app:assembleDebug
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebugAndroidTest
```
في بيئة Android/Code On The Go استخدم أمر البناء المتاح:
```bash
bash ./gradlew :app:assembleDebug
```
المخرج المعتاد: `app/build/outputs/apk/debug/app-debug.apk`. اختبارات Android instrumented تحتاج جهازًا أو محاكيًا منفصلًا:
```bash
./gradlew --no-daemon :app:connectedDebugAndroidTest
```
لا تعلن نجاحها إن لم تُشغل.

## Release محلي بمفتاح المالك
**ممنوع** رفع كلمة المرور أو شهادة التوقيع إلى المستودع. احتفظ بمفتاح `.jks` الأصلي خارج المشروع، واحفظ نسخة آمنة منه. أنشئ محليًا ملفًا باسم `release.properties` بجذر المشروع (مستثنى من git):
```properties
storeFile=/absolute/private/path/owner-release.jks
storePassword=SET_LOCALLY_NOT_IN_GIT
keyAlias=SET_LOCALLY
keyPassword=SET_LOCALLY_NOT_IN_GIT
```
القيم أعلاه *أمثلة توضيحية فقط* وليست مفاتيح فعلية. عند توفر بيانات صحيحة:
```bash
./gradlew --no-daemon :app:assembleRelease
```
يتحقق Gradle من وجود الملف والمفتاح ويوقع Release فقط عند توفر **الأربعة**. في غياب بيانات التوقيع يكون ناتج Release محليًا **غير موقّع وغير صالح للتوزيع بوصفه إصدارًا رسميًا**. لا تشارك مفتاح التوقيع أو عبارة استعادة النسخ مع المطورين.

تحقق من APK الموقّع على بيئة SDK:
```bash
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
sha256sum app/build/outputs/apk/release/app-release.apk
```

## GitHub Actions: Release يدوي
يحتوي المشروع `.github/workflows/android-release.yml` ويعمل فقط عبر `workflow_dispatch`، من **نفس commit** الذي اخترته. في المستودع، أنشئ Environment باسم `release` وفعّل المراجعين المعتمدين، وأضف الأسرار الأربعة داخله:
- `NAJIM_RELEASE_KEYSTORE_B64`: محتوى ملف التوقيع JKS بعد ترميزه Base64 بسطر واحد.
- `NAJIM_RELEASE_STORE_PASSWORD`: كلمة مرور المخزن.
- `NAJIM_RELEASE_KEY_ALIAS`: اسم مفتاح التوقيع.
- `NAJIM_RELEASE_KEY_PASSWORD`: كلمة مرور المفتاح.

يشغّل المسار بناء Debug واختبارات JVM ثم يبني Release موقّعًا ويستخدم `apksigner verify`. عند غياب أي سر يفشل قبل نشر Release. تحميل artifact من Actions لا يعني نشره تلقائيًا على متجر Google Play.

**ملاحظة:** إنشاء APK موقّع للمالك لا يتطلب تخزين بيانات اعتماد Google OAuth في المستودع؛ تهيئة OAuth الإنتاجية لـDrive ما زالت مرتبطة ببصمة شهادة التوقيع الفعلية وإعداد Google Cloud من المالك.

## متطلبات التسليم النهائي (تسجّل نتائجها الفعلية)
- [ ] Build Debug كامل من حالة نظيفة + مخرجاته.
- [ ] JVM unit tests، وInstrumented Room/backup/PDF tests على جهاز أو محاكي.
- [ ] تسجيل الدخول والقفل في حالتي foreground/background، ومع إعادة تشغيل التطبيق.
- [ ] محاسبة فردية وجماعية مع منع التكرار والتراجع عن فشل السطر الثاني.
- [ ] تحقق من التسليمات والمخزون والأرشفة وعدم تعديل الدين.
- [ ] PDF عربي صحيح على A4 مع تعدد الصفحات وتكرار رؤوس الجدول.
- [ ] تصدير نسخة مشفرة + استعادتها على هاتف ثانٍ بمفتاح مختلف.
- [ ] رفض عبارة استرداد خاطئة دون تعديل قاعدة المستخدم.
- [ ] Google Drive يدوي/مجدول بتصريح حقيقي وعطل شبكة بدون فقد سجلات.
- [ ] اختبار عدم رفع أسرار أو بيانات عملاء ضمن Git أو Actions artifacts.
- [ ] Release موقّع + تحقق `apksigner` من SHA-256/BLAKE لاحقًا حسب سياسة المالك.

لا تعتبر هذه الخانات مقبولة ما لم تتوفر أدلة الاختبار ونتائجها. إصدار APK نهائي للعملاء يتطلب موافقة المالك بعد الفحص.
