export const MAX_DOTENV_BYTES = 256 * 1024;

const validName = /^[A-Za-z_][A-Za-z0-9_]*$/;
const escapes: Record<string, string> = { n: "\n", t: "\t" };

const closingQuote = (body: string) => {
  for (let i = 0; i < body.length; i++) {
    if (body[i] === "\\") i++;
    else if (body[i] === '"') return i;
  }
  return -1;
};

export function parseDotenv(text: string) {
  if (new TextEncoder().encode(text).length > MAX_DOTENV_BYTES) throw new Error("That file is larger than 256 KB.");
  const lines = text.split(/\r?\n/);
  const vars = new Map<string, string>();
  const skipped: number[] = [];
  const duplicates = new Set<string>();
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line || line.startsWith("#")) continue;
    const start = i + 1;
    const match = /^(?:export\s+)?([^\s=]+)\s*=\s*(.*)$/.exec(line);
    let value: string | undefined;
    if (match?.[2].startsWith('"')) {
      let body = match[2].slice(1);
      let last = i;
      while (closingQuote(body) < 0 && last + 1 < lines.length) body += `\n${lines[++last]}`;
      const end = closingQuote(body);
      if (end >= 0) {
        i = last;
        value = body.slice(0, end).replace(/\\([nt"\\])/g, (_, c: string) => escapes[c] ?? c);
      }
    } else if (match?.[2].startsWith("'")) {
      const end = match[2].indexOf("'", 1);
      value = end < 0 ? undefined : match[2].slice(1, end);
    } else {
      value = match?.[2].replace(/\s#.*$/, "").trim();
    }
    if (!match || !validName.test(match[1]) || value === undefined) {
      skipped.push(start);
      continue;
    }
    if (vars.has(match[1])) duplicates.add(match[1]);
    vars.set(match[1], value);
  }
  return { vars: [...vars].map(([name, value]) => ({ name, value })), skipped, duplicates: [...duplicates] };
}
