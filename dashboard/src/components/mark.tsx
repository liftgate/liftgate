import Image from "next/image";
import logo from "@/app/icon.png";

export function Mark({ size = 20 }: { size?: number }) {
  return <Image src={logo} alt="" width={size} height={size} className="rounded-md" priority />;
}
