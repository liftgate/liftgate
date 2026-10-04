import { NextResponse, type NextRequest } from "next/server";
import { apiUrl } from "@/lib/api";
import { hasSession } from "@/lib/landing";

export function proxy(request: NextRequest) {
  if (apiUrl() || hasSession((name) => request.cookies.has(name))) return;
  const { pathname, search } = request.nextUrl;
  return NextResponse.redirect(new URL(`/login?${new URLSearchParams({ next: pathname + search })}`, request.url));
}

export const config = { matcher: "/((?!_next/|api/|login(?:/|$)|.*\\.).+)" };
