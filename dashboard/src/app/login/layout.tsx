import { Mark } from "@/components/mark";

export default function LoginLayout({ children }: LayoutProps<"/login">) {
  return (
    <div className="flex flex-1 items-center justify-center">
      <div className="flex w-full max-w-sm flex-col gap-6 rounded-lg border border-graphite-700 bg-graphite-900 p-8">
        <div className="flex justify-center">
          <Mark size={40} />
        </div>
        {children}
      </div>
    </div>
  );
}
