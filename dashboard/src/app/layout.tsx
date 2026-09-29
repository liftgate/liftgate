import type { Metadata, Viewport } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";

const geistSans = Geist({ variable: "--font-geist-sans", subsets: ["latin"] });
const geistMono = Geist_Mono({ variable: "--font-geist-mono", subsets: ["latin"] });

export const metadata: Metadata = {
  metadataBase: new URL(process.env.LIFTGATE_DASHBOARD_URL || "http://localhost:3000"),
  title: { default: "Liftgate", template: "%s · Liftgate" },
  description: "Deploy and manage applications on Liftgate.",
  robots: { index: false, follow: false },
  openGraph: { images: [{ url: "/og.jpg", width: 1200, height: 630, alt: "The Liftgate logo above the line Full-stack hosting. Open source." }] },
};

export const viewport: Viewport = { themeColor: "#0d0b12", colorScheme: "dark" };

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" className={`${geistSans.variable} ${geistMono.variable} h-full scroll-pt-20 antialiased`}>
      <body className="flex min-h-full flex-col font-sans">{children}</body>
    </html>
  );
}
