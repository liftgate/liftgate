import type { NextConfig } from "next";

const dev = process.env.NODE_ENV === "development";
const api = process.env.NEXT_PUBLIC_API_URL ? new URL(process.env.NEXT_PUBLIC_API_URL).origin : undefined;

const csp = [
  "default-src 'self'",
  `script-src 'self' 'unsafe-inline'${dev ? " 'unsafe-eval'" : ""}`,
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob: https:",
  "font-src 'self'",
  `connect-src 'self'${api ? ` ${api} ${api.replace(/^http/, "ws")}` : ""}`,
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
].join("; ");

const nextConfig: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
  headers: async () => [
    {
      source: "/:path*",
      headers: [
        { key: "Content-Security-Policy", value: csp },
        { key: "Strict-Transport-Security", value: "max-age=63072000" },
        { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
        { key: "X-Content-Type-Options", value: "nosniff" },
      ],
    },
    {
      source: "/:path*",
      has: [{ type: "header", key: "accept", value: ".*text/html.*" }],
      headers: [{ key: "Cache-Control", value: "private, no-cache, no-store, max-age=0, must-revalidate, no-transform" }],
    },
  ],
  rewrites: async () => (dev ? [{ source: "/api/:path*", destination: "http://localhost:8080/api/:path*" }] : []),
};

export default nextConfig;
