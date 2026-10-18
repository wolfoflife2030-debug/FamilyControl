package com.parent.familycontrol

import android.app.admin.DeviceAdminReceiver

/**
 * مستقبل سياسة الجهاز (Device Admin). لا حاجة لكتابة منطق داخله؛ مجرد تسجيله
 * ومنحه صلاحية Device Owner عبر ADB هو ما يفتح إمكانية فرض القيود القوية
 * (منع تعديل إعدادات الشبكة، منع الفورمات، منع حذف التطبيق) من MainActivity.
 */
class AdminReceiver : DeviceAdminReceiver()
