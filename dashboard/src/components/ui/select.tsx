import type { SelectHTMLAttributes } from "react";
import { controlClasses } from "./input";

export function Select({ className = "", ...rest }: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select {...rest} className={`${controlClasses} ${className}`} />;
}
