import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { ShieldCheck, AlertTriangle, Mail } from 'lucide-react';
import { PublicLayout } from '../components/public/PublicLayout';
import {
  PublicLang,
  SITE_CONFIG,
  getInitialPublicLang,
  savePublicLang,
} from '../config/site';

interface PolicySection {
  id: string;
  num: string;
  titleAr: string;
  titleEn: string;
  contentAr: React.ReactNode;
  contentEn: React.ReactNode;
}

export const PrivacyPolicyPage: React.FC = () => {
  const [lang, setLang] = useState<PublicLang>(() => getInitialPublicLang());
  const isAr = lang === 'ar';

  const handleToggleLang = () => {
    const next: PublicLang = lang === 'ar' ? 'en' : 'ar';
    setLang(next);
    savePublicLang(next);
  };

  const sections: PolicySection[] = [
    {
      id: 'intro',
      num: '01',
      titleAr: 'مقدمة وتعريف منصة البطل إكسبريس',
      titleEn: 'Introduction & Identification of El Batal Express',
      contentAr: (
        <p>
          توضح سياسة الخصوصية هذه كيفية قيام منصة <strong>البطل إكسبريس (El Batal Express)</strong>{' '}
          (معرّف مشروع Firebase:{' '}
          <code className="font-mono text-amber-400">{SITE_CONFIG.firebaseProjectId}</code>) بجمع واستخدام وتخزين وحماية البيانات الشخصية والتشغيلية عبر مكوناتها: تطبيق التاجر (<code className="font-mono text-xs">{SITE_CONFIG.merchantPackageId}</code>)، وتطبيق الكابتن (<code className="font-mono text-xs">{SITE_CONFIG.captainPackageId}</code>)، ولوحة التحكم والإدارة السحابية، وخدمات الدفع الخلفية المرتبطة ببوابة Paymob.
        </p>
      ),
      contentEn: (
        <p>
          This Privacy Policy explains how <strong>El Batal Express</strong> (Firebase Project ID:{' '}
          <code className="font-mono text-amber-400">{SITE_CONFIG.firebaseProjectId}</code>) collects, uses, stores, and protects personal and operational data across its ecosystem: the Merchant App (<code className="font-mono text-xs">{SITE_CONFIG.merchantPackageId}</code>), the Captain Delivery App (<code className="font-mono text-xs">{SITE_CONFIG.captainPackageId}</code>), the Admin Web Dashboard, and the Paymob payment backend.
        </p>
      ),
    },
    {
      id: 'collected-data',
      num: '02',
      titleAr: 'البيانات الشخصية والتشغيلية التي يتم جمعها فعلياً',
      titleEn: 'Personal & Operational Data Actually Collected',
      contentAr: (
        <ul className="list-disc list-inside space-y-1.5">
          <li>بيانات التعريف والتواصل: الاسم الكامل أو الاسم التجاري، البريد الإلكتروني، رقم الهاتف، العنوان التجاري أو عنوان التوصيل.</li>
          <li>بيانات التحقق والصلاحيات: معرّف المستخدم الفريد (UID)، حالة تأكيد البريد الإلكتروني، ودور الحساب (مسؤول، تاجر، كابتن).</li>
          <li>الملفات والوسائط المرفوعة اختيارياً: شعار التاجر أو الصورة الشخصية للكابتن المخزنة في Firebase Storage.</li>
          <li>بيانات العمليات: تفاصيل الطلبات، مواعيد الشفتات، الإحداثيات الجغرافية أثناء العمل، وسجلات المعاملات والاشتراكات.</li>
        </ul>
      ),
      contentEn: (
        <ul className="list-disc list-inside space-y-1.5">
          <li>Identity & Contact Data: Full or business name, email address, phone number, merchant street address, and order delivery address.</li>
          <li>Account & Role Data: Firebase Auth UID, email verification status, and role flags (admin, merchant, captain).</li>
          <li>Uploaded Media: Merchant logo images and captain profile photos stored in Firebase Storage.</li>
          <li>Operational Data: Order fulfillment records, work shift schedules, active GPS coordinates, and subscription/payment metadata.</li>
        </ul>
      ),
    },
    {
      id: 'accounts-auth',
      num: '03',
      titleAr: 'بيانات الحسابات وتسجيل الدخول',
      titleEn: 'Account Credentials & Authentication Data',
      contentAr: (
        <p>
          تعتمد المنصة على خدمة <strong>Firebase Authentication</strong> لتسجيل الدخول عبر البريد الإلكتروني وكلمة المرور أو عبر حساب Google، والتحقق من البريد الإلكتروني وإعادة تعيين كلمة المرور. بالنسبة لمسؤولي النظام، تُخزن ملفاتهم الإدارية في مجموعة <code className="font-mono">admins</code> مع تقييد إضافة أو حذف المسؤولين بالمسؤول الكامل للنظام وفق قواعد أمان Firestore.
        </p>
      ),
      contentEn: (
        <p>
          User sign-in, password reset, and email verification are handled by <strong>Firebase Authentication</strong> (Email/Password and Google Sign-In). Administrator profiles are recorded in the <code className="font-mono">admins</code> collection, with creation and deletion strictly limited by Firestore security rules to the Master System Administrator.
        </p>
      ),
    },
    {
      id: 'merchants-captains',
      num: '04',
      titleAr: 'البيانات الخاصة بالتجار والكباتن',
      titleEn: 'Merchant & Delivery Captain Data',
      contentAr: (
        <div className="space-y-2">
          <p>
            <strong>بيانات التجار (<code className="font-mono">restaurants</code>):</strong> اسم التاجر، التصنيف، البريد الإلكتروني، رقم الهاتف، العنوان، الشعار، حالة الاشتراك وتواريخ بدايته ونهايته، حالة التفعيل، الإحداثيات الجغرافية للفرع، وعدد الطلبات.
          </p>
          <p>
            <strong>بيانات الكباتن (<code className="font-mono">captains</code> & <code className="font-mono">captain_schedules</code>):</strong> الاسم، البريد الإلكتروني، رقم الهاتف، نوع المركبة، الصورة الشخصية، حالة التواجد، صلاحية استقبال الطلبات، عدد الطلبات، التقييم، وجداول شفتات العمل.
          </p>
        </div>
      ),
      contentEn: (
        <div className="space-y-2">
          <p>
            <strong>Merchants (<code className="font-mono">restaurants</code>):</strong> Store name, business category, email, phone, address, store logo, subscription status and dates, active operational status, store coordinates, and order counts.
          </p>
          <p>
            <strong>Captains (<code className="font-mono">captains</code> & <code className="font-mono">captain_schedules</code>):</strong> Name, email, phone, vehicle type, profile photo, availability status, order-reception status, completed order count, rating, and scheduled shift hours.
          </p>
        </div>
      ),
    },
    {
      id: 'orders-delivery',
      num: '05',
      titleAr: 'بيانات الطلبات ودورة التوصيل',
      titleEn: 'Orders & Delivery Fulfillment Data',
      contentAr: (
        <p>
          تُخزن بيانات الطلبات في مجموعة <code className="font-mono">orders</code> وتشمل: رقم الطلب، معرّف واسم التاجر، معرّف واسم الكابتن، اسم العميل ورقم هاتفه وعنوان التوصيل (اللازمين لتسليم الشحنة)، القيمة ورسوم التوصيل، الملاحظات، والستة توقيتات التشغيلية (إنشاء الطلب، مدة البحث عن كابتن، القبول، الوصول للتاجر، الاستلام، والتسليم للعميل).
        </p>
      ),
      contentEn: (
        <p>
          Orders stored in the <code className="font-mono">orders</code> collection contain order number, merchant ID/name, captain ID/name, customer name, phone, and delivery address (required strictly to fulfill delivery), order amounts/delivery fee, notes, and six lifecycle timestamps (creation, captain search duration, acceptance, merchant arrival, pickup, and delivery completion).
        </p>
      ),
    },
    {
      id: 'geolocation',
      num: '06',
      titleAr: 'بيانات الموقع الجغرافي وصلاحيات الموقع',
      titleEn: 'Geolocation & GPS Coordinates',
      contentAr: (
        <p>
          تستخدم المنصة إحداثيات الموقع الجغرافي (<code className="font-mono">lat</code>, <code className="font-mono">lng</code>) للتجار والكباتن المتصلين فقط أثناء العمل بهدف: (1) عرض المواقع الحية على خريطة غرفة العمليات داخل لوحة الإدارة (باستخدام خرائط OpenStreetMap / Leaflet)، و(2) حساب المسافة الجغرافية لإسناد الطلب تلقائياً إلى أقرب كابتن متاح للتاجر. لا يتم تتبع الموقع الجغرافي للزوار غير المسجلين على الموقع العام.
        </p>
      ),
      contentEn: (
        <p>
          Geographic coordinates (<code className="font-mono">lat</code>, <code className="font-mono">lng</code>) for registered merchants and active online captains are used strictly to: (1) display live operational status on the Admin Operations Map (rendered via Leaflet / OpenStreetMap), and (2) compute distance to assign orders to the nearest eligible captain. Unauthenticated public website visitors are never tracked via GPS.
        </p>
      ),
    },
    {
      id: 'payments-data',
      num: '07',
      titleAr: 'بيانات الدفع والمعاملات المالية',
      titleEn: 'Payment & Subscription Transaction Data',
      contentAr: (
        <p>
          تحتفظ المنصة في مجموعتي <code className="font-mono">payments</code> و <code className="font-mono">subscriptions</code> بالبيانات المرجعية للمعاملات المالية والاشتراكات مثل: رقم المعاملة، اسم ومعرّف التاجر، المبلغ، وسيلة الدفع (نقدي، محفظة إلكترونية، تحويل، أو بطاقة)، حالة الدفع، وتاريخ المعاملة وتفاصيل الباقة (2، 4، 6، أو 8 كباتن). <strong>لا تقوم لوحة التحكم أو قاعدة بيانات Firestore بجمع أو تخزين أرقام البطاقات البنكية الكاملة أو رموز CVV أو الأرقام السرية للبطاقات.</strong>
        </p>
      ),
      contentEn: (
        <p>
          The <code className="font-mono">payments</code> and <code className="font-mono">subscriptions</code> collections record financial reference metadata only: transaction reference ID, merchant ID/name, amount, payment method label, transaction status, date, and subscription package terms (2, 4, 6, or 8 captains). <strong>Neither this Web Dashboard nor Cloud Firestore collects or stores full bank card numbers, CVV codes, or card PINs.</strong>
        </p>
      ),
    },
    {
      id: 'paymob',
      num: '08',
      titleAr: 'استخدام خدمات Paymob في معالجة المدفوعات',
      titleEn: 'Use of Paymob for Payment Processing',
      contentAr: (
        <p>
          تتم معالجة المدفوعات الإلكترونية الخاصة بالاشتراكات والتسويات عبر خادم الخلفية (Backend) المرتبط ببوابة الدفع <strong>Paymob</strong>. عند الدفع عبر Paymob، تُدخل بيانات الدفع الحساسة مباشرة عبر بوابة Paymob الآمنة الخاضعة لمعايير أمان البطاقات (PCI-DSS)، ويتلقى النظام فقط إشعاراً بحالة العملية ورقم المرجع والمبلغ لتحديث حالة الاشتراك أو الدفعة في قاعدة البيانات، دون تخزين بيانات البطاقات لدى البطل إكسبريس.
        </p>
      ),
      contentEn: (
        <p>
          Electronic payment transactions are processed via our backend integration with <strong>Paymob</strong>. Sensitive payment card or wallet credentials are entered and processed directly within Paymob’s PCI-DSS compliant payment environment. El Batal Express receives only transaction status confirmations, reference IDs, and amounts to update subscription and payment records, without storing card credentials on our servers or Firestore.
        </p>
      ),
    },
    {
      id: 'firebase-services',
      num: '09',
      titleAr: 'استخدام خدمات Firebase والخدمات التقنية الفعلية',
      titleEn: 'Actual Firebase & Technical Services Used',
      contentAr: (
        <ul className="list-disc list-inside space-y-1.5">
          <li><strong>Firebase Authentication:</strong> إدارة تسجيل دخول المسؤولين والتجار والكباتن والتحقق من البريد الإلكتروني.</li>
          <li><strong>Cloud Firestore:</strong> قاعدة البيانات السحابية لحفظ سجلات العمليات والاشتراكات والمستخدمين تحت قواعد أمان مشددة.</li>
          <li><strong>Firebase Storage:</strong> تخزين شعارات التجار وصور الكباتن الشخصية.</li>
          <li><strong>Firebase Cloud Messaging (FCM):</strong> إرسال إشعارات فورية للمسؤولين عند وصول طلب جديد أو تعثر تعيين كابتن، عبر تسجيل رمز إشعارات المتصفح في <code className="font-mono">users/{'{uid}'}</code> بعد موافقة المستخدم الصريحة.</li>
          <li><strong>Firebase Analytics & Hosting:</strong> استضافة تطبيق الويب عبر Firebase Hosting وإحصائيات استخدام تقنية مجمعة عبر Google Firebase Analytics عندما يدعمها المتصفح.</li>
        </ul>
      ),
      contentEn: (
        <ul className="list-disc list-inside space-y-1.5">
          <li><strong>Firebase Authentication:</strong> Manages identity authentication and email verification for admins, merchants, and captains.</li>
          <li><strong>Cloud Firestore:</strong> Stores operational, merchant, captain, order, and subscription records protected by strict Role-Based Access Control security rules.</li>
          <li><strong>Firebase Storage:</strong> Stores merchant business logos and captain personal profile photos.</li>
          <li><strong>Firebase Cloud Messaging (FCM):</strong> Sends push alerts to administrators upon new orders or captain assignment failures, storing browser push tokens under <code className="font-mono">users/{'{uid}'}</code> only after explicit browser notification consent.</li>
          <li><strong>Firebase Hosting & Analytics:</strong> Hosts the web application and collects standard aggregated technical analytics via Firebase Analytics when supported by the browser.</li>
        </ul>
      ),
    },
    {
      id: 'google-oauth',
      num: '10',
      titleAr: 'بيانات Google OAuth ونطاقات الوصول (التزام Google API User Data Policy)',
      titleEn: 'Google OAuth Data & Limited Use Policy Compliance',
      contentAr: (
        <div className="space-y-3">
          <p>
            يوفر النظام خيار تسجيل الدخول عبر Google للمسؤولين، كما يتضمن مزود المصادقة (<code className="font-mono">GoogleAuthProvider</code> في <code className="font-mono">src/firebase/config.ts</code>) نطاقات الوصول التالية لخدمتي Google Drive وGoogle Sheets لاستخدامها حصراً في ميزة تصدير التقارير الإدارية (<code className="font-mono">googleSheetsService.ts</code>):
          </p>
          <div className="overflow-x-auto border border-zinc-800 rounded-xl">
            <table className="w-full text-right text-xs border-collapse">
              <thead>
                <tr className="bg-zinc-950/60 border-b border-zinc-800 text-zinc-300">
                  <th className="py-2.5 px-3">النطاق (OAuth Scope)</th>
                  <th className="py-2.5 px-3">السبب الفعلي للاستخدام داخل النظام</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-zinc-800/70">
                {SITE_CONFIG.googleOAuthScopes.map((item) => (
                  <tr key={item.scope}>
                    <td className="py-2.5 px-3 font-mono text-amber-400" dir="ltr">
                      {item.scope}
                    </td>
                    <td className="py-2.5 px-3 text-zinc-300">{item.purposeAr}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ul className="list-disc list-inside space-y-1.5 text-zinc-300">
            <li><strong>الخدمات غير المستخدمة:</strong> لا يصل التطبيق مطلقاً إلى Gmail أو Google Calendar أو Google Contacts أو Google Docs أو Google Photos.</li>
            <li><strong>التخزين المؤقت لرمز الوصول:</strong> يُحفظ رمز الوصول (<code className="font-mono">cachedAccessToken</code>) في الذاكرة المؤقتة لمتصفح المسؤول فقط أثناء الجلسة النشطة ويُمسح فوراً عند تسجيل الخروج؛ ولا يتم تخزين رمز OAuth في Firestore أو قواعد البيانات أو التخزين الدائم.</li>
            <li><strong>عدم المشاركة والالتزام بـ Limited Use:</strong> لا يتم بيع بيانات Google أو استخدامها لأغراض إعلانية أو مشاركتها مع أطراف خارجية أو استخدامها في تدريب نماذج الذكاء الاصطناعي، ويلتزم استخدام ونقل المعلومات الواردة من واجهات Google بـ{' '}
              <a
                href="https://developers.google.com/terms/api-services-user-data-policy"
                target="_blank"
                rel="noopener noreferrer"
                className="text-amber-400 underline"
              >
                Google API Services User Data Policy
              </a>{' '}
              بما في ذلك متطلبات الاستخدام المحدود (Limited Use requirements).
            </li>
            <li><strong>سحب الموافقة والحذف:</strong> يمكن للمستخدم سحب صلاحية الوصول في أي وقت من صفحة أذونات حساب Google على الرابط:{' '}
              <a
                href="https://myaccount.google.com/permissions"
                target="_blank"
                rel="noopener noreferrer"
                className="text-amber-400 underline font-mono"
                dir="ltr"
              >
                https://myaccount.google.com/permissions
              </a>
              .
            </li>
          </ul>
        </div>
      ),
      contentEn: (
        <div className="space-y-3">
          <p>
            El Batal Express supports Google Sign-In for administrators and configures the following Google OAuth scopes in <code className="font-mono">src/firebase/config.ts</code> exclusively for optional admin-initiated operational reporting via Google Sheets (<code className="font-mono">googleSheetsService.ts</code>):
          </p>
          <div className="overflow-x-auto border border-zinc-800 rounded-xl">
            <table className="w-full text-left text-xs border-collapse">
              <thead>
                <tr className="bg-zinc-950/60 border-b border-zinc-800 text-zinc-300">
                  <th className="py-2.5 px-3">OAuth Scope</th>
                  <th className="py-2.5 px-3">Actual Purpose in Codebase</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-zinc-800/70">
                {SITE_CONFIG.googleOAuthScopes.map((item) => (
                  <tr key={item.scope}>
                    <td className="py-2.5 px-3 font-mono text-amber-400">{item.scope}</td>
                    <td className="py-2.5 px-3 text-zinc-300">{item.purposeEn}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ul className="list-disc list-inside space-y-1.5 text-zinc-300">
            <li><strong>Services NOT Accessed:</strong> This application does NOT request or access Gmail, Google Calendar, Google Contacts, Google Docs, or Google Photos.</li>
            <li><strong>Token Storage:</strong> The Google OAuth Access Token (<code className="font-mono">cachedAccessToken</code>) is stored strictly in client session memory and cleared immediately upon sign-out. It is never stored in Firestore or localStorage.</li>
            <li><strong>Sharing & Limited Use Compliance:</strong> Google user data is never sold, never used for advertising, never shared with unrelated third parties, and never used to train AI/ML models. El Batal Express’s use and transfer to any other app of information received from Google APIs strictly adhere to the{' '}
              <a
                href="https://developers.google.com/terms/api-services-user-data-policy"
                target="_blank"
                rel="noopener noreferrer"
                className="text-amber-400 underline"
              >
                Google API Services User Data Policy
              </a>
              , including the Limited Use requirements.
            </li>
            <li><strong>Revoking Consent & Deletion:</strong> You may revoke Google OAuth access at any time via{' '}
              <a
                href="https://myaccount.google.com/permissions"
                target="_blank"
                rel="noopener noreferrer"
                className="text-amber-400 underline font-mono"
              >
                https://myaccount.google.com/permissions
              </a>
              .
            </li>
          </ul>
        </div>
      ),
    },
    {
      id: 'purposes',
      num: '11',
      titleAr: 'الأغراض التي تُستخدم فيها البيانات',
      titleEn: 'Purposes of Data Processing',
      contentAr: (
        <ul className="list-disc list-inside space-y-1.5">
          <li>تشغيل وإدارة عمليات إرسال واستلام وتسليم الطلبات بين التجار والكباتن والعملاء.</li>
          <li>التحقق من صلاحية الحسابات والأدوار ومنع الوصول غير المصرح به للوحة الإدارة أو قواعد البيانات.</li>
          <li>إدارة اشتراكات التجار وتأكيد عمليات السداد والتجديد الشهري.</li>
          <li>إرسال تنبيهات تشغيلية فورية للمسؤولين عند وصول طلبات جديدة أو تعثر إسناد الكباتن.</li>
          <li>إصدار تقارير التشغيل والمحاسبة بصيغ Excel/CSV أو جداول Google Sheets بناءً على طلب المسؤول.</li>
        </ul>
      ),
      contentEn: (
        <ul className="list-disc list-inside space-y-1.5">
          <li>Facilitating end-to-end order creation, nearest-captain matching, and delivery tracking.</li>
          <li>Authenticating users, verifying email ownership, and enforcing Role-Based Access Control.</li>
          <li>Managing merchant monthly subscriptions, renewal dates, and payment verification.</li>
          <li>Delivering operational notifications to administrators on incoming orders or failed dispatches.</li>
          <li>Generating operational and accounting exports (Excel/CSV or admin-authorized Google Sheets).</li>
        </ul>
      ),
    },
    {
      id: 'storage-security',
      num: '12',
      titleAr: 'تخزين البيانات وحمايتها',
      titleEn: 'Data Storage & Security Controls',
      contentAr: (
        <p>
          تُخزن البيانات السحابية على خوادم Google Firebase (Cloud Firestore وFirebase Storage) مع تشفير الاتصال عبر HTTPS/TLS أثناء النقل، وتطبيق قواعد أمان <code className="font-mono">firestore.rules</code> التي تفصل بين صلاحيات المسؤول العام، والمسؤولين، والتجار، والكباتن، وتمنع القراءة أو الكتابة المجهولة للبيانات الحساسة. ولا يتم تخزين مفاتيح Paymob السرية أو مفاتيح حساب الخدمة داخل كود الواجهة الأمامية.
        </p>
      ),
      contentEn: (
        <p>
          Data is stored on Google Firebase infrastructure (Cloud Firestore and Firebase Storage) using HTTPS/TLS encryption in transit and enforced by <code className="font-mono">firestore.rules</code> isolating Master Admin, Admin, Merchant, and Captain permissions. Sensitive backend secrets (such as Paymob API secrets and Firebase Admin service credentials) are never exposed in frontend code.
        </p>
      ),
    },
    {
      id: 'retention',
      num: '13',
      titleAr: 'مدة الاحتفاظ بالبيانات',
      titleEn: 'Data Retention Periods',
      contentAr: (
        <p>
          نحتفظ ببيانات الحسابات النشطة (المسؤولين، التجار، الكباتن) طوال فترة نشاط الحساب على المنصة. وتُحفظ سجلات الطلبات والمدفوعات والاشتراكات للفترة اللازمة لتشغيل المنصة والمراجعة المالية وحل النزاعات أو حتى تقديم طلب حذف موثق ما لم يتطلب القانون الاحتفاظ بسجلات مالية لفترة أطول. أما رموز Google OAuth المؤقتة في الذاكرة فتنتهي فور إغلاق الجلسة أو تسجيل الخروج.
        </p>
      ),
      contentEn: (
        <p>
          Active account records (administrators, merchants, and captains) are retained for as long as the account remains active on the platform. Order, payment, and subscription records are retained as required for operational auditing, billing reconciliation, and legal compliance, or until a verified deletion request is processed. In-memory Google OAuth access tokens expire upon session end or sign-out.
        </p>
      ),
    },
    {
      id: 'third-parties',
      num: '14',
      titleAr: 'الجهات الخارجية التي قد تُشارك معها البيانات وأسباب المشاركة',
      titleEn: 'Third-Party Service Providers & Sharing',
      contentAr: (
        <ul className="list-disc list-inside space-y-1.5">
          <li><strong>Google Firebase / Google Cloud:</strong> لتوفير خدمات المصادقة، قاعدة البيانات، التخزين، الإشعارات الفورية، والاستضافة.</li>
          <li><strong>Paymob:</strong> لمعالجة عمليات الدفع الإلكتروني بأمان وفق معايير مزود الدفع.</li>
          <li><strong>Google Workspace APIs (Sheets / Drive):</strong> فقط عندما يختار المسؤول المصرح له إنشاء أو تصدير جدول بيانات إلى حسابه على Google.</li>
          <li><strong>مزودو الخرائط (OpenStreetMap / Leaflet):</strong> لعرض الخريطة التشغيلية دون إرسال بيانات هوية العملاء الشخصية.</li>
          <li><strong>الالتزام القانوني:</strong> عند وجود طلب قضائي أو تنظيمي ملزم وفق القانون المعمول به.</li>
        </ul>
      ),
      contentEn: (
        <ul className="list-disc list-inside space-y-1.5">
          <li><strong>Google Firebase / Google Cloud:</strong> Authentication, Firestore database, Storage, FCM push notifications, and web hosting.</li>
          <li><strong>Paymob:</strong> Secure processing of electronic subscription and settlement payments.</li>
          <li><strong>Google Workspace APIs (Sheets / Drive):</strong> Only when an authorized administrator explicitly initiates a spreadsheet export to their own Google account.</li>
          <li><strong>Map Tile Providers (OpenStreetMap / Leaflet):</strong> Rendering geographic map tiles without transmitting user PII.</li>
          <li><strong>Legal Compliance:</strong> Where required by applicable law, court order, or regulatory authority.</li>
        </ul>
      ),
    },
    {
      id: 'user-rights',
      num: '15',
      titleAr: 'حقوق المستخدم في الوصول إلى بياناته وتصحيحها وحذفها',
      titleEn: 'User Rights (Access, Correction & Deletion)',
      contentAr: (
        <p>
          يحق للمستخدمين (المسؤولين، التجار، والكباتن) طلب الاطلاع على بياناتهم المخزنة، أو طلب تصحيح البيانات غير الدقيقة (مثل الاسم أو رقم الهاتف أو صورة الملف الشخصي)، أو طلب حذف حساباتهم وسجلاتهم الشخصية أو سحب موافقة الإشعارات الفورية أو أذونات Google OAuth في أي وقت.
        </p>
      ),
      contentEn: (
        <p>
          Users (administrators, merchants, and captains) have the right to request access to their stored personal data, request correction of inaccurate fields (such as name, phone, or profile media), withdraw notification or Google OAuth consent, or request permanent account and personal data deletion.
        </p>
      ),
    },
    {
      id: 'deletion-request',
      num: '16',
      titleAr: 'كيفية طلب حذف الحساب والبيانات',
      titleEn: 'How to Request Account & Data Deletion',
      contentAr: (
        <p>
          يمكن تقديم طلب حذف الحساب أو البيانات الشخصية عبر إرسال رسالة إلكترونية إلى البريد الإداري الموضح أدناه من نفس البريد الإلكتروني المسجل بالحساب مع توضيح نوع الحساب (تاجر، كابتن، أو مسؤول)، كما يمكن إزالة صلاحيات ربط Google من{' '}
          <a
            href="https://myaccount.google.com/permissions"
            target="_blank"
            rel="noopener noreferrer"
            className="text-amber-400 underline font-mono"
            dir="ltr"
          >
            myaccount.google.com/permissions
          </a>
          . تتم مراجعة طلبات الحذف والتحقق من ملكية الحساب قبل تنفيذ الإزالة من Firebase Authentication وFirestore ما لم تكن هناك التزامات مالية قائمة.
        </p>
      ),
      contentEn: (
        <p>
          To request deletion of your account and personal data, email the official contact address below from your registered email address specifying your account role (Merchant, Captain, or Admin). You may also revoke Google account access directly at{' '}
          <a
            href="https://myaccount.google.com/permissions"
            target="_blank"
            rel="noopener noreferrer"
            className="text-amber-400 underline font-mono"
          >
            myaccount.google.com/permissions
          </a>
          . Requests are verified for ownership prior to removing records from Firebase Authentication and Firestore, subject to mandatory financial/legal record retention.
        </p>
      ),
    },
    {
      id: 'data-protection',
      num: '17',
      titleAr: 'حماية بيانات المستخدمين والفئة المستهدفة',
      titleEn: 'Data Protection & Intended Audience',
      contentAr: (
        <p>
          المنصة مخصصة للاستخدام التجاري والتشغيلي بواسطة الأنشطة التجارية البالغة (التجار)، ومندوبي التوصيل المؤهلين (الكباتن 18 عاماً فأكثر)، ومسؤولي النظام المصرح لهم، وليست موجهة للأطفال دون سن 18 عاماً.
        </p>
      ),
      contentEn: (
        <p>
          El Batal Express is intended for commercial and operational use by registered merchants, qualified delivery couriers (18+ years old), and authorized administrators. The platform is not directed at children under 18.
        </p>
      ),
    },
    {
      id: 'cookies',
      num: '18',
      titleAr: 'ملفات تعريف الارتباط والتخزين المحلي بالمتصفح',
      titleEn: 'Cookies & Browser Local Storage',
      contentAr: (
        <p>
          تستخدم واجهة الويب آليات التخزين المحلي بالمتصفح (LocalStorage / SessionStorage / IndexedDB الخاصة بـ Firebase Auth) لحفظ حالة جلسة تسجيل الدخول، وتفضيلات لغة العرض (العربية/الإنجليزية)، وتفضيلات الصوت والإشعارات داخل لوحة التحكم. لا نستخدم ملفات تعريف ارتباط إعلانية أو تتبع طرف ثالث لأغراض التسويق.
        </p>
      ),
      contentEn: (
        <p>
          The web application uses browser storage (LocalStorage, SessionStorage, and Firebase Auth session persistence) solely to maintain authentication sessions, display language preferences, and admin notification/sound preferences. We do not use third-party advertising or cross-site marketing tracking cookies.
        </p>
      ),
    },
    {
      id: 'updates',
      num: '19',
      titleAr: 'تحديثات سياسة الخصوصية',
      titleEn: 'Updates to This Privacy Policy',
      contentAr: (
        <p>
          قد نقوم بتحديث سياسة الخصوصية هذه عند إضافة خدمات جديدة أو تعديل نطاقات التكامل أو المتطلبات التنظيمية، وسيتم نشر النسخة المحدثة على هذه الصفحة مع تحديث تاريخ السريان.
        </p>
      ),
      contentEn: (
        <p>
          We may update this Privacy Policy when adding new platform features, modifying integration scopes, or addressing legal requirements. Updates will be published directly on this page with a revised effective date.
        </p>
      ),
    },
    {
      id: 'contact',
      num: '20',
      titleAr: 'بيانات التواصل الرسمية والبيانات المطلوب استكمالها',
      titleEn: 'Official Contact Information & Pending Legal Disclosures',
      contentAr: (
        <div className="space-y-4">
          <div className="space-y-1.5">
            <p>
              <strong>البريد الإلكتروني لمسؤول النظام المالك:</strong>{' '}
              <a
                href={`mailto:${SITE_CONFIG.adminContactEmail}`}
                className="text-amber-400 hover:underline font-mono"
                dir="ltr"
              >
                {SITE_CONFIG.adminContactEmail}
              </a>
            </p>
            <p>
              <strong>بريد الدعم الفني المسجل في إعدادات النظام:</strong>{' '}
              <span className="font-mono text-zinc-200" dir="ltr">
                {SITE_CONFIG.supportContactEmail}
              </span>
            </p>
          </div>
          <div className="p-4 rounded-xl bg-amber-500/10 border border-amber-500/30 text-amber-200 text-xs leading-relaxed flex items-start gap-3">
            <AlertTriangle className="w-4 h-4 text-amber-400 shrink-0 mt-0.5" />
            <div>
              <strong>تنبيه شفافية للمراجعة:</strong> لم يُعثر في الكود المصدري الحالي على بيانات الكيان القانوني الرسمية (اسم الشركة القانوني المسجل، رقم السجل التجاري/الضريبي، العنوان البريدي الفعلي، أو رقم هاتف خدمة عملاء حقيقي غير الرقم الافتراضي <code className="font-mono">01000000000</code>). يجب استكمال هذه الحقول بالبيانات القانونية الفعلية قبل اعتماد النشر النهائي ومراجعة Google.
            </div>
          </div>
        </div>
      ),
      contentEn: (
        <div className="space-y-4">
          <div className="space-y-1.5">
            <p>
              <strong>Verified System Owner Email:</strong>{' '}
              <a
                href={`mailto:${SITE_CONFIG.adminContactEmail}`}
                className="text-amber-400 hover:underline font-mono"
              >
                {SITE_CONFIG.adminContactEmail}
              </a>
            </p>
            <p>
              <strong>Configured Platform Support Email:</strong>{' '}
              <span className="font-mono text-zinc-200">{SITE_CONFIG.supportContactEmail}</span>
            </p>
          </div>
          <div className="p-4 rounded-xl bg-amber-500/10 border border-amber-500/30 text-amber-200 text-xs leading-relaxed flex items-start gap-3">
            <AlertTriangle className="w-4 h-4 text-amber-400 shrink-0 mt-0.5" />
            <div>
              <strong>Transparency Notice:</strong> The current repository does not contain a registered legal company name, commercial registration / tax ID, verified physical street address, or verified support phone number (only a placeholder <code className="font-mono">01000000000</code> exists in Dashboard Settings). These legal entity fields must be completed by the business owner before final compliance sign-off.
            </div>
          </div>
        </div>
      ),
    },
  ];

  return (
    <PublicLayout lang={lang} onToggleLang={handleToggleLang}>
      <div className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-12 sm:py-16 space-y-10">
        {/* Header */}
        <header className="space-y-4 pb-8 border-b border-zinc-800/80">
          <div className="flex items-center gap-2 text-xs font-semibold text-amber-400">
            <ShieldCheck className="w-4 h-4" />
            <span>
              {isAr
                ? 'وثيقة رسمية عامة · متاحة دون تسجيل دخول'
                : 'Public Official Policy · Accessible Without Authentication'}
            </span>
          </div>

          <h1 className="text-2xl sm:text-4xl font-black text-white leading-tight">
            {isAr
              ? 'سياسة الخصوصية — البطل إكسبريس (El Batal Express)'
              : 'Privacy Policy — El Batal Express'}
          </h1>

          <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-zinc-400">
            <span>{isAr ? 'آخر تحديث: نوفمبر 2026' : 'Last Updated: November 2026'}</span>
            <span aria-hidden="true">·</span>
            <span className="font-mono">{SITE_CONFIG.firebaseProjectId}</span>
            <span aria-hidden="true">·</span>
            <Link to="/terms-of-service" className="text-amber-400 hover:underline">
              {isAr ? 'شروط الاستخدام' : 'Terms of Service'}
            </Link>
          </div>
        </header>

        {/* Sections */}
        <div className="space-y-8">
          {sections.map((section) => (
            <section
              key={section.id}
              id={section.id}
              className="p-6 sm:p-7 rounded-2xl bg-[#10131b] border border-zinc-800/90 space-y-3"
            >
              <div className="flex items-baseline gap-3">
                <span className="text-xs font-mono font-bold text-amber-400 shrink-0">
                  {section.num}.
                </span>
                <h2 className="text-lg sm:text-xl font-extrabold text-white">
                  {isAr ? section.titleAr : section.titleEn}
                </h2>
              </div>
              <div className="text-sm text-zinc-300 leading-relaxed">
                {isAr ? section.contentAr : section.contentEn}
              </div>
            </section>
          ))}
        </div>

        {/* Bottom CTA */}
        <div className="p-6 rounded-2xl bg-[#0c0f16] border border-zinc-800 flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div className="text-xs sm:text-sm text-zinc-300">
            {isAr
              ? 'هل لديك استفسار حول بياناتك أو طلب حذف حساب؟'
              : 'Questions about your personal data or deletion requests?'}
          </div>
          <a
            href={`mailto:${SITE_CONFIG.adminContactEmail}?subject=${encodeURIComponent(
              'Privacy Policy & Data Inquiry - El Batal Express'
            )}`}
            className="inline-flex items-center gap-2 px-4 py-2.5 rounded-xl bg-amber-500 hover:bg-amber-400 text-zinc-950 font-bold text-xs sm:text-sm transition-colors whitespace-nowrap shrink-0"
          >
            <Mail className="w-4 h-4" />
            <span>{isAr ? 'التواصل مع مسؤول الخصوصية' : 'Contact Privacy Administrator'}</span>
          </a>
        </div>
      </div>
    </PublicLayout>
  );
};
