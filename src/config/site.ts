export type PublicLang = 'ar' | 'en';

export const SITE_CONFIG = {
  brandNameAr: 'البطل إكسبريس',
  brandNameEn: 'El Batal Express',
  firebaseProjectId: 'el-batal-express-956ae',
  hostingSiteId: 'elbatalexpress',
  merchantPackageId: 'com.aistudio.elbatal.xydbca',
  captainPackageId: 'com.aistudio.elbatalexpresscaptain.captain',
  // Verified administrative owner contact present in system auth/security rules
  adminContactEmail: 'moamen.elsayed.abdelmoamen@gmail.com',
  // Support contact configured in Dashboard Settings (admin should verify/update before final submission)
  supportContactEmail: 'support@elbatalexpress.com',
  routes: {
    home: '/',
    privacyPolicy: '/privacy-policy',
    termsOfService: '/terms-of-service',
    login: '/login',
    dashboard: '/dashboard',
  },
  // Actual OAuth scopes configured in src/firebase/config.ts
  googleOAuthScopes: [
    {
      scope: 'openid / email / profile',
      purposeAr: 'التحقق من هوية المسؤول وتسجيل الدخول عبر Google Sign-In',
      purposeEn: 'Verify administrator identity and authenticate via Google Sign-In',
    },
    {
      scope: 'https://www.googleapis.com/auth/spreadsheets',
      purposeAr: 'إنشاء وتحديث جداول تقارير الطلبات والتجار والكباتن والمدفوعات عند طلب المسؤول التصدير',
      purposeEn: 'Create and update operational spreadsheets (orders, merchants, captains, payments) when explicitly exported by an admin',
    },
    {
      scope: 'https://www.googleapis.com/auth/spreadsheets.readonly',
      purposeAr: 'قراءة بيانات الجداول المرتبطة وبيانات تعريف الصفحات عند المزامنة',
      purposeEn: 'Read spreadsheet metadata and tab values during admin-initiated sync',
    },
    {
      scope: 'https://www.googleapis.com/auth/drive.file',
      purposeAr: 'الوصول إلى ملفات جداول البيانات التي أنشأها التطبيق لصالح المسؤول',
      purposeEn: 'Access spreadsheet files created by the application on behalf of the admin',
    },
    {
      scope: 'https://www.googleapis.com/auth/drive.readonly',
      purposeAr: 'عرض قائمة ملفات Google Sheets الخاصة بالمستخدم في وحدة التصدير لاختيار الملف المستهدف',
      purposeEn: 'List existing Google Spreadsheets in the user’s Drive so the admin can select a target sheet',
    },
    {
      scope: 'https://www.googleapis.com/auth/drive',
      purposeAr: 'مُدرج في إعدادات مزود Google بالمشروع لدعم إدارة ملفات جداول البيانات المصدّرة',
      purposeEn: 'Configured in the project GoogleAuthProvider for managing exported spreadsheet files',
    },
  ],
  subscriptionPackages: [
    { captains: 2, priceEgp: 400 },
    { captains: 4, priceEgp: 800 },
    { captains: 6, priceEgp: 1200 },
    { captains: 8, priceEgp: 1600 },
  ],
} as const;

export function getPublicOrigin(): string {
  const configured = import.meta.env.VITE_PUBLIC_SITE_URL;
  if (configured && typeof configured === 'string' && configured.trim()) {
    return configured.trim().replace(/\/+$/, '');
  }
  if (typeof window !== 'undefined' && window.location?.origin) {
    return window.location.origin;
  }
  return 'https://YOUR_OWNED_DOMAIN';
}

export function getInitialPublicLang(): PublicLang {
  if (typeof window === 'undefined') return 'ar';
  try {
    const params = new URLSearchParams(window.location.search);
    const qLang = params.get('lang');
    if (qLang === 'en' || qLang === 'ar') {
      localStorage.setItem('elbatal_public_lang', qLang);
      return qLang;
    }
    const saved = localStorage.getItem('elbatal_public_lang');
    if (saved === 'en' || saved === 'ar') return saved;
  } catch {
    // ignore storage errors in strict incognito
  }
  return 'ar';
}

export function savePublicLang(lang: PublicLang): void {
  try {
    if (typeof window !== 'undefined') {
      localStorage.setItem('elbatal_public_lang', lang);
    }
  } catch {
    // ignore
  }
}
