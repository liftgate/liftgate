import type { ButtonHTMLAttributes } from "react";

const variants = {
  primary: "bg-linear-to-bl from-brand-from to-brand-to text-white hover:brightness-110",
  secondary: "border border-graphite-600 bg-graphite-800 text-white hover:border-graphite-400",
  ghost: "text-graphite-200 hover:bg-graphite-800 hover:text-white",
  danger: "border border-danger/40 text-danger hover:bg-danger/10",
};

export type ButtonVariant = keyof typeof variants;

export const buttonClasses = (variant: ButtonVariant = "secondary", className = "") =>
  `inline-flex h-8 shrink-0 items-center justify-center gap-2 rounded-md px-4 text-sm font-medium transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:cursor-not-allowed disabled:opacity-50 ${variants[variant]} ${className}`;

type Props = ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant; pending?: boolean };

export function Button({ variant, pending = false, className, children, disabled, type = "button", ...rest }: Props) {
  return (
    <button {...rest} type={type} disabled={disabled || pending} className={buttonClasses(variant, className)}>
      {pending && <span className="size-3 animate-spin rounded-full border-2 border-current border-t-transparent" />}
      {children}
    </button>
  );
}
