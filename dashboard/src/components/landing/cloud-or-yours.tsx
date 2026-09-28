import type { ReactNode } from "react";
import { buttonClasses } from "@/components/ui/button";
import { AppLink } from "./app-link";
import { repoUrl } from "./links";
import { Section } from "./section";
import { h3, inlineLink } from "./styles";

const strong = (text: string) => <span className="font-medium text-white">{text}</span>;

const columns: { title: string; note: string; rows: [string, ReactNode][]; action: (signedIn: boolean) => ReactNode; className: string }[] = [
  {
    title: "Liftgate Cloud",
    note: "Run by us",
    rows: [
      ["Price", <>{strong("Free while Liftgate is pre-alpha.")} No card and no billing.</>],
      ["Web addresses", "A liftgate.app address with HTTPS for every web and static service."],
      ["Sign-in", "GitHub, passkeys and SAML single sign-on."],
      ["New accounts", "Approved by hand. You can sign in right away and deploy once approved."],
    ],
    action: (signedIn) => <AppLink signedIn={signedIn} size="lg" className="mt-2 justify-self-start" />,
    className: "lg:pr-12",
  },
  {
    title: "Your Kubernetes",
    note: "Run by you",
    rows: [
      [
        "Price",
        <>
          {strong("Free and open source.")} Licensed under{" "}
          <a href={`${repoUrl}/blob/main/LICENSE`} target="_blank" rel="noreferrer" className={inlineLink}>
            AGPL-3.0
          </a>
          .
        </>,
      ],
      ["Web addresses", "Your own wildcard domain, plus custom domains checked with a TXT record."],
      ["Sign-in", "GitHub, passkeys and SAML. Turn on Google, GitLab, Bitbucket and email codes too."],
      ["New accounts", "Open, by approval or closed. Your choice."],
    ],
    action: () => (
      <a href="#self-hosting" className={buttonClasses("secondary", "mt-2 justify-self-start", "lg")}>
        Self-host
      </a>
    ),
    className: "border-t border-graphite-700 pt-12 lg:border-t-0 lg:border-l lg:pt-0 lg:pl-12",
  },
];

export function CloudOrYours({ signedIn }: { signedIn: boolean }) {
  return (
    <Section
      id="cloud-or-yours"
      title={
        <>
          <span className="block">Deploy on our cloud.</span>
          <span className="block">Or on yours.</span>
        </>
      }
      body="Liftgate Cloud runs the open-source Helm chart. Install the same chart on your own Kubernetes and your repositories build the same way."
    >
      <div className="mt-8 grid gap-y-12 md:mt-12 lg:grid-cols-2 lg:grid-rows-[repeat(6,auto)] lg:gap-y-6">
        {columns.map((column) => (
          <div key={column.title} className={`grid gap-y-6 lg:row-span-6 lg:grid-rows-subgrid ${column.className}`}>
            <div>
              <h3 className={h3}>{column.title}</h3>
              <p className="mt-1 text-sm leading-5 text-graphite-400">{column.note}</p>
            </div>
            <dl className="grid gap-y-6 lg:row-span-4 lg:grid-rows-subgrid">
              {column.rows.map(([term, value]) => (
                <div key={term}>
                  <dt className="text-sm leading-5 text-graphite-400">{term}</dt>
                  <dd className="mt-1 text-base leading-6 text-graphite-200">{value}</dd>
                </div>
              ))}
            </dl>
            {column.action(signedIn)}
          </div>
        ))}
      </div>
    </Section>
  );
}
