export const toBuffer = (value: string) =>
  Uint8Array.from(atob(value.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(value.length / 4) * 4, "=")), (c) => c.charCodeAt(0))
    .buffer;

export const toBase64Url = (buffer: ArrayBuffer) =>
  btoa(String.fromCharCode(...new Uint8Array(buffer)))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");

const descriptors = (list?: PublicKeyCredentialDescriptorJSON[]) =>
  list?.map((d) => ({ id: toBuffer(d.id), type: "public-key" as const, transports: d.transports as AuthenticatorTransport[] | undefined }));

export function creationOptions({ publicKey: o }: { publicKey: PublicKeyCredentialCreationOptionsJSON }): PublicKeyCredentialCreationOptions {
  return {
    ...o,
    attestation: o.attestation as AttestationConveyancePreference | undefined,
    challenge: toBuffer(o.challenge),
    user: { ...o.user, id: toBuffer(o.user.id) },
    excludeCredentials: descriptors(o.excludeCredentials),
    extensions: o.extensions as AuthenticationExtensionsClientInputs | undefined,
  };
}

export function requestOptions({ publicKey: o }: { publicKey: PublicKeyCredentialRequestOptionsJSON }): PublicKeyCredentialRequestOptions {
  return {
    ...o,
    challenge: toBuffer(o.challenge),
    allowCredentials: descriptors(o.allowCredentials),
    userVerification: o.userVerification as UserVerificationRequirement | undefined,
    extensions: o.extensions as AuthenticationExtensionsClientInputs | undefined,
  };
}

export function credentialJson(credential: PublicKeyCredential) {
  const r = credential.response as AuthenticatorAttestationResponse | AuthenticatorAssertionResponse;
  return {
    id: credential.id,
    rawId: toBase64Url(credential.rawId),
    type: credential.type,
    clientExtensionResults: credential.getClientExtensionResults(),
    response:
      "attestationObject" in r
        ? { clientDataJSON: toBase64Url(r.clientDataJSON), attestationObject: toBase64Url(r.attestationObject), transports: r.getTransports() }
        : {
            clientDataJSON: toBase64Url(r.clientDataJSON),
            authenticatorData: toBase64Url(r.authenticatorData),
            signature: toBase64Url(r.signature),
            userHandle: r.userHandle ? toBase64Url(r.userHandle) : undefined,
          },
  };
}

const settle = (request: Promise<Credential | null>) =>
  request.then(
    (credential) => {
      if (!credential) throw new Error("No passkey was selected.");
      return credentialJson(credential as PublicKeyCredential);
    },
    (e: unknown) => {
      throw e instanceof DOMException && e.name === "NotAllowedError" ? new Error("The passkey request was cancelled or timed out.") : e;
    },
  );

export const createPasskey = (json: { publicKey: PublicKeyCredentialCreationOptionsJSON }) =>
  settle(navigator.credentials.create({ publicKey: creationOptions(json) }));

export const getPasskey = (json: { publicKey: PublicKeyCredentialRequestOptionsJSON }, mediation?: CredentialMediationRequirement, signal?: AbortSignal) =>
  settle(navigator.credentials.get({ publicKey: requestOptions(json), mediation, signal }));

export const isAbort = (e: unknown) => e instanceof DOMException && e.name === "AbortError";
