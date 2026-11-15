import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { FileText } from 'lucide-react';
import { PublicLayout } from '../components/public/PublicLayout';
import {
  PublicLang,
  SITE_CONFIG,
  getInitialPublicLang,
  savePublicLang,
} from '../config/site';

export const TermsOfServicePage: React.FC = () => {
  const [lang, setLang] = useState<PublicLang>(() => getInitialPublicLang());
  const isAr = lang === 'ar';

  const handleToggleLang = () => {
    const next: PublicLang = lang === 'ar' ? 'en' : 'ar';
    setLang(next);
    savePublicLang(next);
  };

  const terms = [
    {
      num: '01',
      titleAr: 'قبول الشروط ونطاق الخدمة',
      titleEn: 'Acceptance of Terms & Scope of Service',
      bodyAr:
        'باستخدامك لمنصة البطل إكسبريس (El Batal Express) سواء عبر لوحة الإدارة أو تطبيق التاجر أو تطبيق الكابتن، فإنك توافق على الالتزام بهذه الشروط والأحكام وبسياسة الخصوصية المعتمدة للمنصة.',
      bodyEn:
        'By accessing or using the El Batal Express Admin Dashboard, Merchant Application, or Captain Delivery Application, you agree to be bound by these Terms of Service and our Privacy Policy.',
    },
    {
      num: '02',
      titleAr: 'الحسابات والأدوار والصلاحيات',
      titleEn: 'Accounts, Roles & Access Control',
      bodyAr:
        'تُمنح صلاحيات الوصول وفق الدور الوظيفي لكل حساب (مسؤول نظام، تاجر، كابتن توصيل). يلتزم كل مستخدم بالحفاظ على سرية بيانات دخوله، ويُحظر محاولة الوصول إلى بيانات حسابات أخرى أو تجاوز قواعد الحماية.',
      bodyEn:
        'Platform access is strictly role-based (System Administrator, Merchant, Delivery Captain). Users are responsible for maintaining the confidentiality of their credentials and must not attempt unauthorized access to restricted records.',
    },
    {
      num: '03',
      titleAr: 'التزامات التجار والكباتن',
      titleEn: 'Merchant & Captain Responsibilities',
      bodyAr:
        'يلتزم التاجر بإدخال بيانات طلبات صحيحة وعناوين تسليم دقيقة والالتزام بباقة الاشتراك السارية (2 أو 4 أو 6 أو 8 كباتن). كما يلتزم الكابتن بتنفيذ الطلبات المسندة إليه بأمانة وتحديث حالة التوصيل والالتزام بمواعيد الشفتات المجدولة.',
      bodyEn:
        'Merchants must provide accurate pickup/delivery order details and maintain an active subscription plan (2, 4, 6, or 8 captains). Captains must fulfill accepted deliveries diligently, update order stages truthfully, and adhere to scheduled shift hours.',
    },
    {
      num: '04',
      titleAr: 'الاشتراكات والمدفوعات عبر Paymob',
      titleEn: 'Subscriptions & Paymob Payment Processing',
      bodyAr:
        'تُدار باقات اشتراكات التجار الشهريّة (400 / 800 / 1200 / 1600 جنيه مصري) وتُعالج المدفوعات الإلكترونية عبر بوابة Paymob أو وسائل الدفع المعتمدة، وتخضع عمليات السداد والتجديد لتأكيد حالة المعاملة المرجعية.',
      bodyEn:
        'Monthly merchant subscription plans (400 / 800 / 1200 / 1600 EGP) and electronic settlements are processed through Paymob or authorized payment channels, subject to verified transaction confirmation.',
    },
    {
      num: '05',
      titleAr: 'التكامل الاختياري مع Google OAuth وGoogle Sheets',
      titleEn: 'Optional Google OAuth & Google Sheets Integration',
      bodyAr:
        'يُستخدم تسجيل الدخول بحساب Google وميزة تصدير جداول البيانات حصراً من قِبل المسؤولين المصرح لهم، وتظل جميع عمليات التصدير خاضعة لسياسة بيانات مستخدمي Google API.',
      bodyEn:
        'Google Sign-In and optional Google Sheets export functionality are available strictly to authorized administrators and remain subject to the Google API Services User Data Policy.',
    },
    {
      num: '06',
      titleAr: 'إيقاف الحساب أو إنهاء الخدمة',
      titleEn: 'Account Suspension & Termination',
      bodyAr:
        'يحق لإدارة المنصة إيقاف استقبال الطلبات أو تعليق الحساب في حال انتهاء الاشتراك، أو مخالفة قواعد التشغيل والأمان، أو عند وجود طلب حذف رسمي من صاحب الحساب.',
      bodyEn:
        'El Batal Express administration reserves the right to pause order reception or suspend accounts upon subscription expiration, security or policy violations, or verified account deletion requests.',
    },
    {
      num: '07',
      titleAr: 'التواصل بشأن الشروط',
      titleEn: 'Contact Regarding These Terms',
      bodyAr: `لأي استفسار قانوني أو تشغيلي يتعلق بشروط الاستخدام، يرجى التواصل عبر البريد الإلكتروني: ${SITE_CONFIG.adminContactEmail}`,
      bodyEn: `For operational or legal inquiries regarding these Terms of Service, contact: ${SITE_CONFIG.adminContactEmail}`,
    },
  ];

  return (
    <PublicLayout lang={lang} onToggleLang={handleToggleLang}>
      <div className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-12 sm:py-16 space-y-10">
        <header className="space-y-4 pb-8 border-b border-zinc-800/80">
          <div className="flex items-center gap-2 text-xs font-semibold text-amber-400">
            <FileText className="w-4 h-4" />
            <span>
              {isAr
                ? 'الشروط العامة للاستخدام · متاحة دون تسجيل دخول'
                : 'Public Terms of Service · Accessible Without Authentication'}
            </span>
          </div>
          <h1 className="text-2xl sm:text-4xl font-black text-white leading-tight">
            {isAr
              ? 'شروط الاستخدام — البطل إكسبريس (El Batal Express)'
              : 'Terms of Service — El Batal Express'}
          </h1>
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-zinc-400">
            <span>{isAr ? 'آخر تحديث: نوفمبر 2026' : 'Last Updated: November 2026'}</span>
            <span aria-hidden="true">·</span>
            <Link to="/privacy-policy" className="text-amber-400 hover:underline">
              {isAr ? 'سياسة الخصوصية' : 'Privacy Policy'}
            </Link>
          </div>
        </header>

        <div className="space-y-6">
          {terms.map((item) => (
            <section
              key={item.num}
              className="p-6 sm:p-7 rounded-2xl bg-[#10131b] border border-zinc-800/90 space-y-3"
            >
              <div className="flex items-baseline gap-3">
                <span className="text-xs font-mono font-bold text-amber-400 shrink-0">
                  {item.num}.
                </span>
                <h2 className="text-lg font-extrabold text-white">
                  {isAr ? item.titleAr : item.titleEn}
                </h2>
              </div>
              <p className="text-sm text-zinc-300 leading-relaxed">
                {isAr ? item.bodyAr : item.bodyEn}
              </p>
            </section>
          ))}
        </div>
      </div>
    </PublicLayout>
  );
};
