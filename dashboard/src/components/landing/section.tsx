import type { ReactNode } from "react";
import { h2 } from "./styles";

export function Section({ id, title, body, children }: { id: string; title: ReactNode; body?: ReactNode; children: ReactNode }) {
  return (
    <section id={id} aria-labelledby={`${id}-title`} className="mt-24 md:mt-40 xl:mt-52">
      <div className="max-w-3xl">
        <h2 id={`${id}-title`} className={h2}>
          {title}
        </h2>
        {body && <p className="mt-4 max-w-2xl text-base leading-6 text-graphite-400 md:text-lg md:leading-7">{body}</p>}
      </div>
      {children}
    </section>
  );
}
