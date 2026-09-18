import type { Metadata } from "next";
import { apiUrl } from "@/lib/api";
import { Mark } from "@/components/mark";
import { buttonClasses } from "@/components/ui/button";

export const metadata: Metadata = { title: "Sign in" };

export default function LoginPage() {
  return (
    <div className="flex flex-1 items-center justify-center">
      <div className="flex w-full max-w-sm flex-col items-center gap-6 rounded-lg border border-graphite-700 bg-graphite-900 p-8 text-center">
        <Mark size={40} />
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Sign in to Liftgate</h1>
          <p className="mt-2 text-sm text-graphite-400">Liftgate asks GitHub for your public profile and email address.</p>
        </div>
        <a href={`${apiUrl()}/api/v1/auth/github/login`} className={buttonClasses("primary", "w-full")}>
          Continue with GitHub
        </a>
      </div>
    </div>
  );
}
