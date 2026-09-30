import { Shell } from "@/components/shell";
import NotFound from "./(dashboard)/not-found";

export { metadata } from "./(dashboard)/not-found";

export default function RootNotFound() {
  return (
    <Shell>
      <NotFound />
    </Shell>
  );
}
