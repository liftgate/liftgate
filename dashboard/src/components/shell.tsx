import type { ReactNode } from "react";
import { Footer } from "./footer";
import { Nav } from "./nav";

export function Shell({ children }: { children: ReactNode }) {
  return (
    <>
      <Nav />
      <main className="mx-auto flex w-full max-w-6xl flex-1 flex-col px-6 py-8">{children}</main>
      <Footer />
    </>
  );
}
