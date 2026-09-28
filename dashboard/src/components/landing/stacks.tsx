import {
  siAngular,
  siAstro,
  siBun,
  siDjango,
  siFastapi,
  siFlask,
  siGo,
  siLaravel,
  siNextdotjs,
  siNodedotjs,
  siNuxt,
  siRubyonrails,
  siRust,
  siSpringboot,
  siSvelte,
  siVite,
} from "simple-icons";
import { inlineLink } from "./styles";

const stacks = [
  { name: "Next.js", icon: siNextdotjs },
  { name: "Nuxt", icon: siNuxt },
  { name: "Astro", icon: siAstro },
  { name: "SvelteKit", icon: siSvelte },
  { name: "Vite", icon: siVite },
  { name: "Angular", icon: siAngular },
  { name: "Node.js", icon: siNodedotjs },
  { name: "Bun", icon: siBun },
  { name: "Django", icon: siDjango },
  { name: "FastAPI", icon: siFastapi },
  { name: "Flask", icon: siFlask },
  { name: "Ruby on Rails", icon: siRubyonrails },
  { name: "Laravel", icon: siLaravel },
  { name: "Spring Boot", icon: siSpringboot },
  { name: "Go", icon: siGo },
  { name: "Rust", icon: siRust },
];

export function Stacks() {
  return (
    <section aria-label="Stacks Railpack detects" className="mt-16 md:mt-24">
      <p className="text-sm leading-5 text-graphite-400">
        No Dockerfile?{" "}
        <a href="https://railpack.com" target="_blank" rel="noreferrer" className={inlineLink}>
          Railpack
        </a>{" "}
        detects these stacks and more. If your repository has one, Liftgate builds the Dockerfile instead.
      </p>
      <ul className="mt-8 grid grid-cols-[repeat(4,auto)] justify-between gap-y-8 md:grid-cols-[repeat(8,auto)]">
        {stacks.map(({ name, icon }) => (
          <li key={name} className="flex">
            <svg viewBox="0 0 24 24" role="img" aria-label={name} className="size-8 fill-current text-graphite-400">
              <path d={icon.path} />
            </svg>
          </li>
        ))}
      </ul>
    </section>
  );
}
