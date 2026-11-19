package com.example.util

import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctionsException

object FirebaseErrorParser {

    /**
     * Translates Firebase and network exceptions into clear, friendly Arabic messages.
     * Maps Cloud Functions, Firestore, and Auth error codes safely.
     */
    fun parseErrorMessage(throwable: Throwable?, defaultMsg: String = "حدث خطأ غير متوقع"): String {
        if (throwable == null) return defaultMsg

        if (throwable is FirebaseFunctionsException) {
            val serverMsg = throwable.message?.trim().orEmpty()
            return when (throwable.code) {
                FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                    serverMsg.ifBlank { "جلسة تسجيل الدخول منتهية، يرجى تسجيل الدخول مجدداً." }
                FirebaseFunctionsException.Code.PERMISSION_DENIED ->
                    serverMsg.ifBlank { "عفواً، ليس لديك الصلاحية لتنفيذ هذا الإجراء." }
                FirebaseFunctionsException.Code.NOT_FOUND ->
                    serverMsg.ifBlank { "الطلب المطلوب غير موجود أو لم يعد متاحاً." }
                FirebaseFunctionsException.Code.ALREADY_EXISTS ->
                    serverMsg.ifBlank { "تم قبول هذا الطلب بالفعل بواسطة كابتن آخر." }
                FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
                    serverMsg.ifBlank { "لا يمكن تنفيذ العملية على الطلب في حالته الحالية." }
                FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
                    serverMsg.ifBlank { "البيانات المرسلة غير صالحة." }
                FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                    "انتهت مهلة استجابة الخادم، يرجى إعادة المحاولة."
                FirebaseFunctionsException.Code.UNAVAILABLE ->
                    "تعذر الاتصال بخدمة الخادم حالياً، يرجى التحقق من الإنترنت وإعادة المحاولة."
                else -> serverMsg.ifBlank { defaultMsg }
            }
        }

        // Handle Firestore specific error codes
        if (throwable is FirebaseFirestoreException) {
            return when (throwable.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    "عفواً، ليس لديك الصلاحية لتنفيذ هذا الإجراء أو انتهت صلاحية الجلسة."
                FirebaseFirestoreException.Code.UNAUTHENTICATED ->
                    "جلسة تسجيل الدخول منتهية، يرجى تسجيل الدخول مجدداً."
                FirebaseFirestoreException.Code.UNAVAILABLE ->
                    "تعذر الاتصال بقاعدة البيانات حالياً، يرجى التحقق من اتصال الإنترنت والمحاولة مرة أخرى."
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ->
                    "انتهت مهلة استجابة الخادم، يرجى إعادة المحاولة."
                FirebaseFirestoreException.Code.NOT_FOUND ->
                    "العنصر المطلوب غير موجود أو تم حذفه من قِبل الإدارة."
                FirebaseFirestoreException.Code.ALREADY_EXISTS ->
                    "البيانات مسجلة بالفعل في النظام."
                FirebaseFirestoreException.Code.FAILED_PRECONDITION ->
                    "لا يمكن إتمام العملية في الحالة الحالية، يرجى تحديث البيانات."
                FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED ->
                    "تم تجاوز الحد المسموح للعمليات، يرجى الانتظار قليلاً ثم المحاولة."
                else -> throwable.localizedMessage ?: defaultMsg
            }
        }

        // Handle Firebase Auth errors
        if (throwable is FirebaseAuthException) {
            val code = throwable.errorCode.lowercase()
            return when {
                code.contains("invalid-credential") || code.contains("wrong-password") ->
                    "بيانات الاعتماد أو كلمة المرور غير صحيحة، يرجى التحقق وإعادة المحاولة."
                code.contains("user-not-found") ->
                    "لا يوجد حساب مسجل بهذه البيانات."
                code.contains("user-disabled") ->
                    "تم تعطيل هذا الحساب، يرجى التواصل مع إدارة البطل إكسبريس."
                code.contains("too-many-requests") ->
                    "تم حظر المحاولات مؤقتاً بسبب تكرار المحاولة، يرجى الانتظار بضع دقائق."
                code.contains("network-request-failed") ->
                    "تعذر الاتصال، يرجى التحقق من اتصال الإنترنت."
                else -> throwable.localizedMessage ?: defaultMsg
            }
        }

        val msg = throwable.message?.lowercase() ?: ""
        return when {
            msg.contains("permission_denied") || msg.contains("missing or insufficient permissions") ->
                "عفواً، ليس لديك الصلاحية لتنفيذ هذا الإجراء."
            msg.contains("unauthenticated") ->
                "يرجى تسجيل الدخول أولاً لتنفيذ هذا الإجراء."
            msg.contains("unavailable") || msg.contains("network") || msg.contains("timeout") ->
                "تعذر الاتصال بالشبكة، يرجى التحقق من اتصالك بالإنترنت."
            msg.contains("تم استلام هذا الطلب بواسطة كابتن آخر") ->
                "تم استلام هذا الطلب بالفعل بواسطة كابتن آخر."
            msg.contains("خارج ساعات العمل") ->
                "عفواً، أنت خارج ساعات العمل الرسمية ولا يمكنك قبول طلبات جديدة حالياً."
            else -> throwable.localizedMessage ?: defaultMsg
        }
    }
}
