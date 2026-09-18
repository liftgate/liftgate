import type { InputHTMLAttributes, ReactNode } from "react";

export const controlClasses =
  "h-8 rounded-md border border-graphite-600 bg-graphite-950 px-3 text-sm text-white placeholder:text-graphite-400 focus:border-accent focus:outline-none disabled:opacity-50";

export function Input({ className = "", ...rest }: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...rest} className={`${controlClasses} ${className}`} />;
}

export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="flex flex-col gap-2 text-sm">
      <span className="font-medium text-graphite-200">{label}</span>
      {children}
      {hint && <span className="text-xs text-graphite-400">{hint}</span>}
    </label>
  );
}

export function FormError({ message }: { message?: string }) {
  return message ? (
    <p role="alert" className="text-sm text-danger">
      {message}
    </p>
  ) : null;
}
