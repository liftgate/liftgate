"use client";

import Image from "next/image";
import { useEffect, useRef, useState, useSyncExternalStore, type Dispatch, type SetStateAction } from "react";
import { Button } from "@/components/ui/button";

type State = "idle" | "starting" | "playing" | "paused" | "ended" | "error";

const controls: Record<Exclude<State, "error">, [string, string]> = {
  idle: ["Play", "Play the showreel"],
  starting: ["Play", "Play the showreel"],
  playing: ["Pause", "Pause the showreel"],
  paused: ["Play", "Play the showreel"],
  ended: ["Replay", "Replay the showreel"],
};

const noop = () => () => {};

function start(video: HTMLVideoElement | null, setState: Dispatch<SetStateAction<State>>) {
  if (!video) return;
  setState((s) => (s === "error" ? s : "starting"));
  video.play().catch(() => setState((s) => (s === "error" ? s : "idle")));
}

export function Showreel({ transcript }: { transcript: string }) {
  const frame = useRef<HTMLElement>(null);
  const video = useRef<HTMLVideoElement>(null);
  const userPaused = useRef(false);
  const [state, setState] = useState<State>("idle");
  const mounted = useSyncExternalStore(noop, () => true, () => false);

  useEffect(() => {
    const element = video.current;
    if (!element || !frame.current) return;
    const saveData = (navigator as Navigator & { connection?: { saveData?: boolean } }).connection?.saveData;
    const autoplay = !matchMedia("(prefers-reduced-motion: reduce)").matches && !saveData;
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.intersectionRatio < 0.5) element.pause();
        else if (autoplay && !userPaused.current && !element.ended && element.paused) start(element, setState);
      },
      { threshold: 0.5 },
    );
    observer.observe(frame.current);
    return () => observer.disconnect();
  }, []);

  const fail = () => setState("error");
  const shown = state === "playing" || state === "paused";
  const control = state !== "error" && controls[state];

  return (
    <figure ref={frame} aria-label="Liftgate showreel" className="relative aspect-video overflow-hidden rounded-lg border border-graphite-700 bg-graphite-950">
      <Image
        src="/landing/showreel-poster.jpg"
        alt=""
        fill
        preload
        fetchPriority="high"
        sizes="(min-width: 1280px) 634px, (min-width: 1024px) 55vw, calc(100vw - 48px)"
        className="object-cover"
      />
      <video
        ref={video}
        muted
        playsInline
        preload="none"
        aria-describedby="showreel-transcript"
        onPlaying={() => setState("playing")}
        onPause={(e) => setState(e.currentTarget.ended ? "ended" : "paused")}
        onEnded={() => setState("ended")}
        onError={fail}
        className={`absolute inset-0 size-full object-cover transition-opacity duration-500 motion-reduce:transition-none ${shown ? "opacity-100" : "opacity-0"}`}
      >
        <source src="/landing/showreel.webm" type="video/webm" />
        <source src="/landing/showreel.mp4" type="video/mp4" onError={fail} />
      </video>
      {mounted && control && (
        <Button
          aria-label={control[1]}
          pending={state === "starting"}
          onClick={() => {
            userPaused.current = state === "playing";
            if (state === "playing") video.current?.pause();
            else start(video.current, setState);
          }}
          className="absolute right-4 bottom-4"
        >
          {control[0]}
        </Button>
      )}
      <figcaption id="showreel-transcript" className="sr-only">
        {transcript}
      </figcaption>
    </figure>
  );
}
