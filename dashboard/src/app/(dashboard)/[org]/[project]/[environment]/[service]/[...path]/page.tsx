import { notFound } from "next/navigation";

export { metadata } from "@/app/(dashboard)/not-found";

export default function Missing() {
  notFound();
}
