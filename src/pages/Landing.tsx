import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import {
  Store,
  Bike,
  LayoutDashboard,
  CreditCard,
  ArrowLeft,
  ArrowRight,
  MapPin,
  Clock,
  ShieldCheck,
  Bell,
  FileSpreadsheet,
  Mail,
} from 'lucide-react';
import { PublicLayout } from '../components/public/PublicLayout';
import {
  PublicLang,
  SITE_CONFIG,
  getInitialPublicLang,
  savePublicLang,
} from '../config/site';
import heroDispatchImg from '../assets/images/hero_elbatal_dispatch_1794780267627.jpg';
import operationsImg from '../assets/images/ecosystem_operations_1794780268920.jpg';

export const LandingPage: React.FC = () => {
  const [lang, setLang] = useState<PublicLang>(() => getInitialPublicLang());
  const isAr = lang === 'ar';

  const handleToggleLang = () => {
    const next: PublicLang = lang === 'ar' ? 'en' : 'ar';
    setLang(next);
    savePublicLang(next);
  };

  const ArrowIcon = isAr ? ArrowLeft : ArrowRight;

  const platformComponents = [
    {
      num: '01',
      icon: Store,
      titleAr: 'تطبيق التاجر (Merchant App)',
      titleEn: 'Merchant Application',
      descAr:
        'يتيح للتجار والمتاجر والمطاعم إدارة حساباتهم وحالة التشغيل، إرسال طلبات توصيل جديدة، متابعة مراحل تنفيذ الطلب، والاطلاع على تفاصيل باقة الاشتراك وتاريخ التجديد.',
      descEn:
        'Enables merchants and restaurants to manage store operations, submit delivery dispatch requests, monitor live order fulfillment stages, and track subscription plan validity.',
      idLabel: SITE_CONFIG.merchantPackageId,
    },
    {
      num: '02',
      icon: Bike,
      titleAr: 'تطبيق الكابتن (مندوب التوصيل)',
      titleEn: 'Captain (Courier) Application',
      descAr:
        'يمكّن كباتن التوصيل من تحديث حالة التواجد والورديات، مشاركة الموقع الجغرافي أثناء العمل، استقبال الطلبات المسندة إليهم، وتوثيق مراحل الوصول للتاجر والاستلام والتسليم للعميل.',
      descEn:
        'Allows delivery captains to manage availability and shift schedules, share live GPS coordinates during active operations, accept assigned orders, and confirm arrival, pickup, and final customer delivery.',
      idLabel: SITE_CONFIG.captainPackageId,
    },
    {
      num: '03',
      icon: LayoutDashboard,
      titleAr: 'لوحة التحكم والإدارة المركزية',
      titleEn: 'Central Admin Operations Dashboard',
      descAr:
        'واجهة إدارية متكاملة لمراقبة الخريطة المباشرة، إيجاد أقرب كابتن متاح للتاجر، إدارة مواعيد الشفتات، مراجعة توقيتات الطلبات الستة، إدارة اشتراكات التجار، وتصدير التقارير.',
      descEn:
        'Centralized web command panel for real-time fleet map visibility, nearest-captain auto-dispatch, captain shift verification, 6-stage order timestamp auditing, subscription management, and report exports.',
      idLabel: 'Firebase Web Dashboard',
    },
    {
      num: '04',
      icon: CreditCard,
      titleAr: 'نظام الاشتراكات ومعالجة المدفوعات (Paymob)',
      titleEn: 'Subscriptions & Paymob Payment Backend',
      descAr:
        'تنظيم باقات اشتراك التجار الشهرية ومتابعة تواريخ البدء والانتهاء وحالات السداد، مع معالجة المدفوعات الإلكترونية عبر بوابة Paymob دون تخزين بيانات البطاقات البنكية داخل الواجهة أو قاعدة البيانات.',
      descEn:
        'Manages merchant monthly subscription packages, start/expiry tracking, and payment transaction statuses, integrating with Paymob backend checkout without storing raw card credentials in the frontend or Firestore.',
      idLabel: 'Paymob Payment Gateway',
    },
  ];

  const workflowSteps = [
    {
      step: '01',
      icon: Store,
      titleAr: 'إنشاء طلب التوصيل من التاجر أو الإدارة',
      titleEn: 'Order Creation by Merchant or Admin',
      descAr:
        'يُنشئ التاجر طلب التوصيل محدداً عنوان العميل ورسوم التوصيل وتفاصيل الاستلام، أو تقوم غرفة العمليات بإدخال الطلب وإرساله مباشرة.',
      descEn:
        'A merchant submits a delivery request with customer destination and delivery fee, or the dispatch team creates an order directly from the admin panel.',
    },
    {
      step: '02',
      icon: MapPin,
      titleAr: 'تحديد أقرب كابتن متاح جغرافياً',
      titleEn: 'Nearest Eligible Captain Matching',
      descAr:
        'يبحث النظام تلقائياً عن أقرب كابتن متصل ومفعّل لاستقبال الطلبات داخل نطاق شفت العمل باستخدام الإحداثيات الجغرافية وحساب المسافة.',
      descEn:
        'The dispatch engine checks active shift schedules and order-reception permissions, then computes geographic distance to match the nearest eligible captain to the merchant.',
    },
    {
      step: '03',
      icon: Clock,
      titleAr: 'تتبع التوقيتات الستة لدورة الطلب',
      titleEn: 'Six-Stage Timestamp Tracking',
      descAr:
        'توثيق وقت إنشاء الطلب، مدة البحث عن الكابتن، وقت قبول الكابتن، وقت الوصول للتاجر، وقت استلام الطلب، ووقت التسليم النهائي للعميل.',
      descEn:
        'Every order records creation time, captain search duration, acceptance time, merchant arrival time, pickup time, and customer delivery completion time.',
    },
    {
      step: '04',
      icon: Bell,
      titleAr: 'إشعارات فورية وتقارير مالية منظمة',
      titleEn: 'Instant Alerts & Financial Reporting',
      descAr:
        'تنبيهات لحظية عبر Firebase Cloud Messaging عند وصول الطلبات أو تعثر التعيين، مع سجل للمدفوعات والاشتراكات وإمكانية التصدير إلى Excel أو Google Sheets.',
      descEn:
        'Real-time Firebase Cloud Messaging notifications alert admins on new orders or dispatch failures, paired with structured subscription ledgers and Excel/Google Sheets exports.',
    },
  ];

  return (
    <PublicLayout lang={lang} onToggleLang={handleToggleLang}>
      {/* Hero Section */}
      <section className="relative border-b border-zinc-800/80 overflow-hidden">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-14 lg:py-20">
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-10 lg:gap-12 items-stretch">
            {/* Editorial Copy Column */}
            <div className="lg:col-span-6 flex flex-col justify-between space-y-8">
              <div className="space-y-5">
                <p className="text-xs sm:text-sm font-semibold text-amber-400 tracking-wide">
                  {isAr
                    ? 'البطل إكسبريس · منظومة إدارة التوصيل والأسطول'
                    : 'El Batal Express · Delivery Operations & Fleet Platform'}
                </p>

                <h1
                  className="font-black text-white leading-[1.18] tracking-tight"
                  style={{
                    fontSize: 'clamp(2rem, 3.6vw, 3.25rem)',
                    textWrap: 'balance',
                  }}
                >
                  {isAr
                    ? 'إدارة متكاملة لطلبات التوصيل بين التجار والكباتن وغرفة العمليات'
                    : 'Unified Delivery Dispatch Connecting Merchants, Captains, and Operations'}
                </h1>

                <p className="text-sm sm:text-base text-zinc-300 leading-relaxed max-w-xl">
                  {isAr
                    ? 'توفر منصة البطل إكسبريس بنية تشغيلية كاملة لشركات التوصيل والأنشطة التجارية، تشمل تطبيق التاجر لإرسال الطلبات، وتطبيق الكابتن لتنفيذ التوصيل والتتبع الجغرافي، ولوحة تحكم مركزية للإشراف اللحظي وإدارة الاشتراكات والمدفوعات.'
                    : 'El Batal Express provides an operational delivery management suite connecting a Merchant App for dispatch requests, a Captain App for live GPS fulfillment, and a Central Web Dashboard for fleet coordination, subscriptions, and financial reporting.'}
                </p>
              </div>

              {/* Primary CTA & Clear Portals */}
              <div className="space-y-5">
                <div className="flex flex-wrap items-center gap-4">
                  <Link
                    to="/login"
                    className="inline-flex items-center gap-2.5 px-6 py-3.5 rounded-xl bg-amber-500 hover:bg-amber-400 text-zinc-950 font-black text-sm transition-colors whitespace-nowrap shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-300"
                  >
                    <span>
                      {isAr ? 'تسجيل دخول المسؤولين والإدارة' : 'Administrator Dashboard Sign In'}
                    </span>
                    <ArrowIcon className="w-4 h-4" />
                  </Link>

                  <Link
                    to="/privacy-policy"
                    className="inline-flex items-center gap-2 px-5 py-3.5 rounded-xl border border-zinc-700 hover:border-amber-500/50 text-zinc-200 hover:text-white font-bold text-sm transition-colors whitespace-nowrap shrink-0"
                  >
                    <ShieldCheck className="w-4 h-4 text-amber-400" />
                    <span>{isAr ? 'سياسة الخصوصية' : 'Privacy Policy'}</span>
                  </Link>
                </div>

                <div className="pt-4 border-t border-zinc-800/80 flex flex-wrap items-center gap-x-4 gap-y-2 text-xs text-zinc-400">
                  <span>{isAr ? 'تطبيق التاجر' : 'Merchant App'}</span>
                  <span aria-hidden="true">·</span>
                  <span>{isAr ? 'تطبيق الكابتن' : 'Captain App'}</span>
                  <span aria-hidden="true">·</span>
                  <span>{isAr ? 'لوحة الإدارة السحابية' : 'Cloud Admin Panel'}</span>
                  <span aria-hidden="true">·</span>
                  <span>{isAr ? 'مدفوعات Paymob' : 'Paymob Checkout'}</span>
                </div>
              </div>
            </div>

            {/* Hero Visual Column */}
            <div className="lg:col-span-6 flex">
              <div className="relative w-full min-h-[280px] lg:min-h-[400px] rounded-2xl overflow-hidden border border-zinc-800 bg-[#11141c]">
                <img
                  src={heroDispatchImg}
                  alt={
                    isAr
                      ? 'منظومة التوصيل السريع وإدارة الأسطول - البطل إكسبريس'
                      : 'El Batal Express urban delivery courier dispatch'
                  }
                  className="w-full h-full object-cover"
                  referrerPolicy="no-referrer"
                />
                <div className="absolute inset-0 bg-gradient-to-t from-[#090b10] via-[#090b10]/25 to-transparent" />
                <div className="absolute bottom-5 inset-x-5 flex flex-wrap items-center justify-between gap-3 text-xs text-zinc-200">
                  <span className="font-semibold">
                    {isAr
                      ? 'متابعة لحظية للطلبات والمسارات الميدانية'
                      : 'Real-time order dispatch & route coordination'}
                  </span>
                  <span className="font-mono text-amber-400">El Batal Express</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* Section 01: Platform Ecosystem */}
      <section id="ecosystem" className="border-b border-zinc-800/80 py-16 lg:py-20">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 space-y-12">
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-10 items-center">
            <div className="lg:col-span-6 space-y-4">
              <p className="text-xs font-semibold text-amber-400">
                {isAr ? '01. هيكل المنصة الفعلي' : '01. Actual System Architecture'}
              </p>
              <h2
                className="text-2xl sm:text-3xl font-black text-white leading-snug"
                style={{ textWrap: 'balance' }}
              >
                {isAr
                  ? 'أربعة مكونات مترابطة لإدارة دورة التوصيل بالكامل'
                  : 'Four Connected Modules Powering the Delivery Lifecycle'}
              </h2>
              <p className="text-sm text-zinc-400 leading-relaxed">
                {isAr
                  ? 'صُممت منصة البطل إكسبريس لربط المتاجر والمطاعم بأسطول مناديب التوصيل عبر بنية سحابية تعتمد على Firebase Authentication وCloud Firestore وFirebase Hosting ومعالجة المدفوعات عبر Paymob، دون عرض أو مشاركة أي بيانات خاصة للعملاء أو الحسابات مع الزوار غير المصرح لهم.'
                  : 'El Batal Express connects registered commercial merchants with delivery captains through a secure cloud architecture built on Firebase Authentication, Cloud Firestore, Firebase Storage, Firebase Hosting, and Paymob backend payment processing.'}
              </p>
            </div>

            <div className="lg:col-span-6">
              <div className="rounded-2xl overflow-hidden border border-zinc-800 bg-[#11141c] aspect-[4/3]">
                <img
                  src={operationsImg}
                  alt={
                    isAr
                      ? 'مركز إدارة عمليات التوصيل والربط بين التطبيقات'
                      : 'Delivery operations workstation and route visualization'
                  }
                  className="w-full h-full object-cover"
                  referrerPolicy="no-referrer"
                />
              </div>
            </div>
          </div>

          {/* 4 Architectural Components */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            {platformComponents.map((item) => {
              const Icon = item.icon;
              return (
                <div
                  key={item.num}
                  className="p-6 sm:p-7 rounded-2xl bg-[#10131b] border border-zinc-800/90 flex flex-col justify-between space-y-5"
                >
                  <div className="space-y-3">
                    <div className="flex items-center justify-between text-xs text-zinc-400">
                      <span className="font-mono font-bold text-amber-400">{item.num}</span>
                      <Icon className="w-5 h-5 text-amber-400" />
                    </div>
                    <h3 className="text-lg font-extrabold text-white">
                      {isAr ? item.titleAr : item.titleEn}
                    </h3>
                    <p className="text-sm text-zinc-400 leading-relaxed">
                      {isAr ? item.descAr : item.descEn}
                    </p>
                  </div>
                  <div className="pt-4 border-t border-zinc-800/70 text-xs text-zinc-500 font-mono">
                    {item.idLabel}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </section>

      {/* Section 02: How the System Works */}
      <section className="border-b border-zinc-800/80 py-16 lg:py-20 bg-[#0b0e14]">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 space-y-12">
          <div className="max-w-2xl space-y-3">
            <p className="text-xs font-semibold text-amber-400">
              {isAr ? '02. آلية العمل الميدانية' : '02. Operational Workflow'}
            </p>
            <h2 className="text-2xl sm:text-3xl font-black text-white">
              {isAr
                ? 'كيف تنتقل الطلبات من التاجر حتى باب العميل'
                : 'How Orders Move From Merchant Dispatch to Final Delivery'}
            </h2>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
            {workflowSteps.map((step) => {
              const Icon = step.icon;
              return (
                <div
                  key={step.step}
                  className="p-6 rounded-2xl bg-[#10131b] border border-zinc-800/90 space-y-4"
                >
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-mono font-bold text-amber-400 tabular-nums">
                      {step.step}
                    </span>
                    <Icon className="w-5 h-5 text-zinc-400" />
                  </div>
                  <h3 className="text-base font-bold text-white leading-snug">
                    {isAr ? step.titleAr : step.titleEn}
                  </h3>
                  <p className="text-xs sm:text-sm text-zinc-400 leading-relaxed">
                    {isAr ? step.descAr : step.descEn}
                  </p>
                </div>
              );
            })}
          </div>
        </div>
      </section>

      {/* Section 03: Subscription Packages & Capabilities */}
      <section className="border-b border-zinc-800/80 py-16 lg:py-20">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 space-y-10">
          <div className="flex flex-col md:flex-row md:items-end justify-between gap-4">
            <div className="space-y-2 max-w-2xl">
              <p className="text-xs font-semibold text-amber-400">
                {isAr ? '03. باقات اشتراك التجار وإدارة التقارير' : '03. Merchant Packages & Reporting'}
              </p>
              <h2 className="text-2xl sm:text-3xl font-black text-white">
                {isAr
                  ? 'خطط تشغيل مرنة حسب حجم أسطول الكباتن'
                  : 'Structured Fleet Capacity Plans for Registered Merchants'}
              </h2>
            </div>
            <p className="text-xs sm:text-sm text-zinc-400">
              {isAr
                ? 'الاشتراكات الشهرية المتوفرة فعلياً داخل النظام'
                : 'Active monthly capacity plans supported by the platform'}
            </p>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-5">
            {SITE_CONFIG.subscriptionPackages.map((pkg) => (
              <div
                key={pkg.captains}
                className="p-6 rounded-2xl bg-[#10131b] border border-zinc-800/90 space-y-3"
              >
                <p className="text-xs font-semibold text-zinc-400">
                  {isAr ? 'باقة أسطول مخصصة' : 'Monthly Merchant Plan'}
                </p>
                <div className="text-2xl font-black text-white tabular-nums">
                  {pkg.captains} {isAr ? 'كابتن' : 'Captains'}
                </div>
                <div className="text-lg font-extrabold text-amber-400 tabular-nums">
                  {pkg.priceEgp} {isAr ? 'جنيه / شهرياً' : 'EGP / month'}
                </div>
                <p className="text-xs text-zinc-500 pt-2 border-t border-zinc-800/70">
                  {isAr
                    ? 'إدارة مخصصة لطلبات التاجر مع متابعة التواريخ والتجديد'
                    : 'Dedicated delivery capacity with automated start and expiry tracking'}
                </p>
              </div>
            ))}
          </div>

          {/* Google Workspace / Excel integration summary */}
          <div className="p-6 sm:p-8 rounded-2xl bg-[#10131b] border border-zinc-800/90 flex flex-col md:flex-row md:items-center justify-between gap-6">
            <div className="space-y-2 max-w-2xl">
              <div className="flex items-center gap-2 text-xs font-semibold text-amber-400">
                <FileSpreadsheet className="w-4 h-4" />
                <span>
                  {isAr ? 'الشفافية وحماية بيانات Google OAuth' : 'Transparency & Google OAuth Data Protection'}
                </span>
              </div>
              <h3 className="text-lg font-extrabold text-white">
                {isAr
                  ? 'تصدير التقارير والالتزام بسياسة استخدام بيانات Google'
                  : 'Operational Exports & Google API Services User Data Compliance'}
              </h3>
              <p className="text-xs sm:text-sm text-zinc-400 leading-relaxed">
                {isAr
                  ? 'تستخدم المنصة تسجيل الدخول عبر Google للتحقق من هوية المسؤولين المصرح لهم، وتتيح تصدير تقارير العمليات في ملفات Excel/CSV أو مزامنتها مع Google Sheets فقط عند طلب المسؤول المختص. يمكنك مراجعة تفاصيل الصلاحيات والبيانات في سياسة الخصوصية.'
                  : 'El Batal Express uses Google Sign-In for administrator authentication and optional Google Sheets/Drive scopes solely for admin-triggered operational report exports. Full scope disclosures and user rights are documented in our Privacy Policy.'}
              </p>
            </div>
            <Link
              to="/privacy-policy"
              className="px-5 py-3 rounded-xl bg-zinc-900 hover:bg-zinc-800 border border-zinc-700 text-zinc-100 text-xs sm:text-sm font-bold transition-colors whitespace-nowrap shrink-0"
            >
              {isAr ? 'قراءة سياسة الخصوصية الكاملة' : 'Read Full Privacy Policy'}
            </Link>
          </div>
        </div>
      </section>

      {/* Section 04: Dedicated Access Portals & Support */}
      <section className="py-16 lg:py-20 bg-[#0b0e14]">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 items-start">
            <div className="lg:col-span-5 space-y-3">
              <p className="text-xs font-semibold text-amber-400">
                {isAr ? '04. بوابات الدخول والدعم الرسمي' : '04. Access Portals & Support'}
              </p>
              <h2 className="text-2xl sm:text-3xl font-black text-white">
                {isAr ? 'الوصول حسب نوع الحساب' : 'Role-Separated System Access'}
              </h2>
              <p className="text-sm text-zinc-400 leading-relaxed">
                {isAr
                  ? 'تعمل لوحة الإدارة بصلاحيات مقيدة للمسؤولين المعتمدين فقط عبر Firebase Authentication وقواعد أمان Firestore، بينما يستخدم التجار والكباتن تطبيقات الهاتف المخصصة لكل فئة.'
                  : 'The Web Dashboard is restricted to verified system administrators via Firebase Authentication and Firestore RBAC rules, while Merchants and Captains use their dedicated mobile applications.'}
              </p>
            </div>

            <div className="lg:col-span-7 grid grid-cols-1 sm:grid-cols-2 gap-5">
              {/* Admin Portal Card */}
              <div className="p-6 rounded-2xl bg-[#10131b] border border-zinc-800/90 flex flex-col justify-between space-y-5">
                <div className="space-y-2">
                  <h3 className="text-base font-extrabold text-white">
                    {isAr ? 'بوابة الإدارة وغرفة العمليات' : 'Admin & Dispatch Portal'}
                  </h3>
                  <p className="text-xs sm:text-sm text-zinc-400 leading-relaxed">
                    {isAr
                      ? 'مخصصة لمسؤولي النظام المعتمدين لإدارة التجار والكباتن والطلبات والاشتراكات والتقارير المالية.'
                      : 'For authorized administrators managing merchants, captains, order dispatch, subscriptions, and analytics.'}
                  </p>
                </div>
                <Link
                  to="/login"
                  className="inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl bg-amber-500 hover:bg-amber-400 text-zinc-950 font-bold text-xs sm:text-sm transition-colors whitespace-nowrap"
                >
                  <span>{isAr ? 'تسجيل دخول الإدارة' : 'Sign In to Admin Panel'}</span>
                  <ArrowIcon className="w-4 h-4" />
                </Link>
              </div>

              {/* Merchant & Support Card */}
              <div className="p-6 rounded-2xl bg-[#10131b] border border-zinc-800/90 flex flex-col justify-between space-y-5">
                <div className="space-y-2">
                  <h3 className="text-base font-extrabold text-white">
                    {isAr ? 'التجار والدعم الفني' : 'Merchants & Technical Support'}
                  </h3>
                  <p className="text-xs sm:text-sm text-zinc-400 leading-relaxed">
                    {isAr
                      ? 'لتفعيل حساب تاجر جديد أو دعم تطبيق التاجر وتطبيق الكابتن أو طلبات الخصوصية، تواصل عبر البريد الرسمي للمشروع.'
                      : 'For merchant onboarding, mobile app support, or privacy & account deletion inquiries, contact the project team directly.'}
                  </p>
                </div>
                <a
                  href={`mailto:${SITE_CONFIG.adminContactEmail}?subject=${encodeURIComponent(
                    'El Batal Express - Merchant / Support Inquiry'
                  )}`}
                  className="inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl bg-zinc-900 hover:bg-zinc-800 border border-zinc-700 text-zinc-100 font-bold text-xs sm:text-sm transition-colors whitespace-nowrap"
                >
                  <Mail className="w-4 h-4 text-amber-400" />
                  <span>{isAr ? 'مراسلة الإدارة والدعم' : 'Contact Administration'}</span>
                </a>
              </div>
            </div>
          </div>
        </div>
      </section>
    </PublicLayout>
  );
};
