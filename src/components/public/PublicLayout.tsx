import React, { useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { Globe, Menu, X } from 'lucide-react';
import appLogo from '../../assets/images/elbatal_logo_1789202744301.jpg';
import { PublicLang, SITE_CONFIG } from '../../config/site';

interface PublicLayoutProps {
  lang: PublicLang;
  onToggleLang: () => void;
  children: React.ReactNode;
}

export const PublicLayout: React.FC<PublicLayoutProps> = ({
  lang,
  onToggleLang,
  children,
}) => {
  const location = useLocation();
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const isAr = lang === 'ar';

  const navLinks = [
    {
      to: '/',
      label: isAr ? 'الرئيسية' : 'Home',
    },
    {
      to: '/#ecosystem',
      label: isAr ? 'منظومة العمل' : 'Platform',
      isAnchor: true,
    },
    {
      to: '/privacy-policy',
      label: isAr ? 'سياسة الخصوصية' : 'Privacy Policy',
    },
    {
      to: '/terms-of-service',
      label: isAr ? 'شروط الاستخدام' : 'Terms of Service',
    },
  ];

  const isActivePath = (to: string) => {
    if (to.includes('#')) return false;
    return location.pathname === to;
  };

  return (
    <div
      dir={isAr ? 'rtl' : 'ltr'}
      lang={isAr ? 'ar' : 'en'}
      className="min-h-screen bg-[#090b10] text-zinc-100 flex flex-col selection:bg-amber-500 selection:text-zinc-950"
    >
      {/* Top Bar Contract: 3 zones — Brand wordmark | 4 single-line nav links | Primary action */}
      <header className="sticky top-0 z-40 bg-[#090b10]/90 backdrop-blur-md border-b border-zinc-800/80">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between gap-8">
          {/* Zone 1: Clean single-line Brand Wordmark */}
          <Link
            to="/"
            className="flex items-center gap-3 whitespace-nowrap shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400 rounded-lg"
          >
            <img
              src={appLogo}
              alt={isAr ? 'شعار البطل إكسبريس' : 'El Batal Express Logo'}
              className="w-9 h-9 rounded-lg object-contain bg-black border border-zinc-800 shrink-0"
              referrerPolicy="no-referrer"
            />
            <span className="text-base sm:text-lg font-extrabold tracking-tight text-white whitespace-nowrap shrink-0">
              {isAr ? 'البطل إكسبريس' : 'El Batal Express'}
            </span>
          </Link>

          {/* Zone 2: Concise single-line navigation links */}
          <nav
            aria-label={isAr ? 'التنقل العام' : 'Public navigation'}
            className="hidden md:flex items-center gap-7 text-sm font-medium text-zinc-300"
          >
            {navLinks.map((item) =>
              item.isAnchor ? (
                <a
                  key={item.to}
                  href={item.to}
                  className="hover:text-amber-400 hover:underline underline-offset-8 decoration-amber-400/60 transition-colors whitespace-nowrap shrink-0"
                >
                  {item.label}
                </a>
              ) : (
                <Link
                  key={item.to}
                  to={item.to}
                  className={`transition-colors whitespace-nowrap shrink-0 hover:text-amber-400 hover:underline underline-offset-8 ${
                    isActivePath(item.to)
                      ? 'text-amber-400 underline decoration-amber-400 font-semibold'
                      : 'decoration-amber-400/60'
                  }`}
                >
                  {item.label}
                </Link>
              )
            )}
            <button
              type="button"
              onClick={onToggleLang}
              className="inline-flex items-center gap-1.5 text-xs font-semibold text-zinc-400 hover:text-white transition-colors whitespace-nowrap shrink-0 cursor-pointer"
              aria-label={isAr ? 'Switch to English' : 'التبديل إلى العربية'}
            >
              <Globe className="w-3.5 h-3.5 text-amber-400" />
              <span>{isAr ? 'English' : 'العربية'}</span>
            </button>
          </nav>

          {/* Zone 3: Single Primary CTA + Mobile menu toggle */}
          <div className="flex items-center gap-3 shrink-0">
            <Link
              to="/login"
              className="px-4 py-2 rounded-lg bg-amber-500 hover:bg-amber-400 text-zinc-950 font-bold text-xs sm:text-sm transition-colors whitespace-nowrap shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-300"
            >
              {isAr ? 'دخول الإدارة' : 'Admin Sign In'}
            </Link>

            <button
              type="button"
              onClick={() => setMobileMenuOpen((prev) => !prev)}
              className="md:hidden p-2 rounded-lg text-zinc-300 hover:text-white hover:bg-zinc-800/70 transition-colors"
              aria-label={isAr ? 'فتح القائمة' : 'Toggle menu'}
            >
              {mobileMenuOpen ? <X className="w-5 h-5" /> : <Menu className="w-5 h-5" />}
            </button>
          </div>
        </div>

        {/* Mobile navigation drawer */}
        {mobileMenuOpen && (
          <div className="md:hidden border-t border-zinc-800 bg-[#0c0f16] px-4 py-4 space-y-2">
            {navLinks.map((item) =>
              item.isAnchor ? (
                <a
                  key={item.to}
                  href={item.to}
                  onClick={() => setMobileMenuOpen(false)}
                  className="block py-2.5 px-3 rounded-lg text-sm font-medium text-zinc-200 hover:bg-zinc-800/70"
                >
                  {item.label}
                </a>
              ) : (
                <Link
                  key={item.to}
                  to={item.to}
                  onClick={() => setMobileMenuOpen(false)}
                  className={`block py-2.5 px-3 rounded-lg text-sm font-medium ${
                    isActivePath(item.to)
                      ? 'bg-amber-500/15 text-amber-400'
                      : 'text-zinc-200 hover:bg-zinc-800/70'
                  }`}
                >
                  {item.label}
                </Link>
              )
            )}
            <button
              type="button"
              onClick={() => {
                onToggleLang();
                setMobileMenuOpen(false);
              }}
              className="w-full flex items-center gap-2 py-2.5 px-3 rounded-lg text-sm font-medium text-zinc-300 hover:bg-zinc-800/70"
            >
              <Globe className="w-4 h-4 text-amber-400" />
              <span>{isAr ? 'Switch to English' : 'التبديل إلى العربية'}</span>
            </button>
          </div>
        )}
      </header>

      {/* Main Content */}
      <main className="flex-1">{children}</main>

      {/* Quiet, authoritative Footer */}
      <footer className="border-t border-zinc-800/80 bg-[#07090d]">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-12">
          <div className="grid grid-cols-1 md:grid-cols-3 gap-10">
            <div className="space-y-3">
              <div className="flex items-center gap-3">
                <img
                  src={appLogo}
                  alt={isAr ? 'البطل إكسبريس' : 'El Batal Express'}
                  className="w-9 h-9 rounded-lg object-contain bg-black border border-zinc-800"
                  referrerPolicy="no-referrer"
                />
                <span className="text-base font-extrabold text-white">
                  {SITE_CONFIG.brandNameEn} · {SITE_CONFIG.brandNameAr}
                </span>
              </div>
              <p className="text-xs sm:text-sm text-zinc-400 leading-relaxed max-w-sm">
                {isAr
                  ? 'منظومة متكاملة لإدارة خدمات التوصيل والطلبات وربط التجار بكباتن التوصيل ولوحة الإدارة المركزية.'
                  : 'An integrated delivery operations platform connecting merchants, delivery captains, and centralized fleet dispatch administration.'}
              </p>
            </div>

            <div className="space-y-3">
              <h3 className="text-sm font-bold text-white">
                {isAr ? 'الروابط القانونية والتنقل' : 'Legal & Navigation'}
              </h3>
              <ul className="space-y-2 text-xs sm:text-sm text-zinc-400">
                <li>
                  <Link to="/" className="hover:text-amber-400 transition-colors">
                    {isAr ? 'الصفحة الرئيسية' : 'Home Page'}
                  </Link>
                </li>
                <li>
                  <Link to="/privacy-policy" className="hover:text-amber-400 transition-colors">
                    {isAr ? 'سياسة الخصوصية (Privacy Policy)' : 'Privacy Policy'}
                  </Link>
                </li>
                <li>
                  <Link to="/terms-of-service" className="hover:text-amber-400 transition-colors">
                    {isAr ? 'شروط الاستخدام (Terms of Service)' : 'Terms of Service'}
                  </Link>
                </li>
                <li>
                  <Link to="/login" className="hover:text-amber-400 transition-colors">
                    {isAr ? 'بوابة تسجيل دخول الإدارة' : 'Administrator Sign In'}
                  </Link>
                </li>
              </ul>
            </div>

            <div className="space-y-3">
              <h3 className="text-sm font-bold text-white">
                {isAr ? 'التواصل والدعم الفني' : 'Support & Official Contact'}
              </h3>
              <div className="space-y-2 text-xs sm:text-sm text-zinc-400">
                <p>
                  {isAr ? 'مسؤول النظام:' : 'System Owner:'}{' '}
                  <a
                    href={`mailto:${SITE_CONFIG.adminContactEmail}`}
                    className="text-amber-400 hover:underline font-mono"
                    dir="ltr"
                  >
                    {SITE_CONFIG.adminContactEmail}
                  </a>
                </p>
                <p>
                  {isAr ? 'بريد الدعم المسجل بالمنصة:' : 'Configured Support Email:'}{' '}
                  <span className="text-zinc-300 font-mono" dir="ltr">
                    {SITE_CONFIG.supportContactEmail}
                  </span>
                </p>
                <p className="text-xs text-zinc-500">
                  {isAr ? 'معرف مشروع Firebase:' : 'Firebase Project ID:'}{' '}
                  <span className="font-mono text-zinc-400">{SITE_CONFIG.firebaseProjectId}</span>
                </p>
              </div>
            </div>
          </div>

          <div className="mt-10 pt-6 border-t border-zinc-800/70 flex flex-col sm:flex-row items-center justify-between gap-4 text-xs text-zinc-500">
            <p>
              © {new Date().getFullYear()} {SITE_CONFIG.brandNameEn} ({SITE_CONFIG.brandNameAr}).{' '}
              {isAr ? 'جميع الحقوق محفوظة.' : 'All rights reserved.'}
            </p>
            <div className="flex items-center gap-4">
              <Link to="/privacy-policy" className="hover:text-zinc-300 transition-colors">
                {isAr ? 'سياسة الخصوصية' : 'Privacy Policy'}
              </Link>
              <span aria-hidden="true">·</span>
              <Link to="/terms-of-service" className="hover:text-zinc-300 transition-colors">
                {isAr ? 'شروط الاستخدام' : 'Terms of Service'}
              </Link>
            </div>
          </div>
        </div>
      </footer>
    </div>
  );
};
