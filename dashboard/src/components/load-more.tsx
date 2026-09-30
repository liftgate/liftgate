import type { ReactNode } from "react";
import type { usePages } from "@/lib/hooks";
import { Button } from "./ui/button";
import { FormError } from "./ui/input";

export function LoadMore({ pages, children }: { pages: Pick<ReturnType<typeof usePages>, "more" | "hasMore">; children: ReactNode }) {
  return (
    <>
      <FormError message={pages.more.error} />
      {pages.hasMore && (
        <div className="flex justify-center">
          <Button pending={pages.more.pending} onClick={() => pages.more.run()}>
            {children}
          </Button>
        </div>
      )}
    </>
  );
}
