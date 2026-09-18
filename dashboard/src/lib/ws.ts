import { useEffect, useState } from "react";
import { apiUrl } from "./api";

export type SocketStatus = "connecting" | "open" | "closed" | "error";

const wsUrl = (path: string) => `${(apiUrl() || window.location.origin).replace(/^http/, "ws")}/api/v1${path}`;

export function useLogSocket(path: string) {
  const [lines, setLines] = useState<string[]>([]);
  const [status, setStatus] = useState<SocketStatus>("connecting");
  useEffect(() => {
    const socket = new WebSocket(wsUrl(path));
    socket.onopen = () => setStatus("open");
    socket.onmessage = (event) => setLines((l) => [...l.slice(-4999), String(event.data)]);
    socket.onerror = () => setStatus("error");
    socket.onclose = () => setStatus((s) => (s === "error" ? s : "closed"));
    return () => socket.close();
  }, [path]);
  return { lines, status };
}
