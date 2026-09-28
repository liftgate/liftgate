import { useEffect, useState } from "react";
import { apiUrl } from "./api";
import { stripAnsi } from "./util";

export type SocketStatus = "connecting" | "open" | "closed" | "error";

const MAX_LINES = 5000;

const wsUrl = (path: string) => `${(apiUrl() || window.location.origin).replace(/^http/, "ws")}/api/v1${path}`;

export function useLogSocket(path: string) {
  const [lines, setLines] = useState<string[]>([]);
  const [status, setStatus] = useState<SocketStatus>("connecting");
  useEffect(() => {
    const pending: string[] = [];
    let frame = 0;
    const flush = () => {
      frame = 0;
      const batch = pending.splice(0);
      setLines((l) => [...l, ...batch].slice(-MAX_LINES));
    };
    const socket = new WebSocket(wsUrl(path));
    socket.onopen = () => setStatus("open");
    socket.onmessage = (event) => {
      if (pending.push(stripAnsi(String(event.data))) > MAX_LINES) pending.shift();
      frame ||= requestAnimationFrame(flush);
    };
    socket.onerror = () => setStatus("error");
    socket.onclose = () => setStatus((s) => (s === "error" ? s : "closed"));
    return () => {
      cancelAnimationFrame(frame);
      socket.close();
    };
  }, [path]);
  return { lines, status };
}
