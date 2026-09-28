"use client";

import { useLinkStatus } from "next/link";
import { Spinner } from "@/components/ui/button";

export function LinkPending() {
  return useLinkStatus().pending ? <Spinner /> : null;
}
