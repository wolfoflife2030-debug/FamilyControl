# Net Control

حجب الواي فاي والبلوتوث على أجهزة الأطفال عبر Device Owner.

## البناء عبر GitHub
1. ارفع محتوى هذا المجلد كاملاً إلى مستودع جديد (فرع main).
2. افتح تبويب Actions، وسيعمل "Build APK" تلقائياً (أو شغّله يدوياً بـ Run workflow).
3. بعد النجاح، نزّل app-debug.apk من قسم Artifacts أسفل صفحة التشغيل.

## التفعيل على جهاز الطفل
1. احذف كل الحسابات مؤقتاً (الإعدادات > الحسابات) وتأكد من عدم وجود مستخدمين إضافيين.
2. فعّل USB debugging.
3. من الكمبيوتر:
   adb install app-debug.apk
   adb shell dpm set-device-owner com.family.netcontrol/.AdminReceiver
4. افتح التطبيق وأنشئ الرقم السري.
5. أعد الحسابات، ثم عطّل USB debugging وخيارات المطوّر.
