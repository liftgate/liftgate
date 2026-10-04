import { StackIcon } from "@/components/icons";
import { stacks } from "@/lib/stacks";
import { inlineLink } from "./styles";

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
      <ul className="mt-8 grid grid-cols-4 gap-y-8 md:grid-cols-8">
        {stacks.map(({ id, name }) => (
          <li key={name} className="flex flex-col items-center gap-2 text-center text-xs text-graphite-400">
            <StackIcon id={id} className="size-8" />
            {name}
          </li>
        ))}
      </ul>
    </section>
  );
}
