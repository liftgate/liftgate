export function Mark({ size = 20 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden="true">
      <rect width="32" height="32" rx="8" className="fill-graphite-800" />
      <path d="M16 6 6 16h6v10h8V16h6z" className="fill-accent" />
    </svg>
  );
}
