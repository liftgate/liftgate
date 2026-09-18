import assert from "node:assert/strict";
import { test } from "node:test";
import { creationOptions, credentialJson, requestOptions, toBase64Url, toBuffer } from "./webauthn.ts";

const bytes = (buffer: BufferSource | undefined) => [...new Uint8Array(buffer as ArrayBuffer)];

test("base64url round trips without padding", () => {
  for (const value of ["", "AA", "AAE", "_-8", "3q2-7w", "SGVsbG8gd29ybGQ"]) assert.equal(toBase64Url(toBuffer(value)), value);
  assert.deepEqual(bytes(toBuffer("_-8")), [255, 239]);
});

test("creation options decode the server json", () => {
  const options = creationOptions({
    publicKey: {
      rp: { id: "liftgate.dev", name: "Liftgate" },
      user: { id: "AAECAw", name: "dean", displayName: "Dean" },
      challenge: "3q2-7w",
      pubKeyCredParams: [{ type: "public-key", alg: -7 }],
      excludeCredentials: [{ id: "BAU", type: "public-key", transports: ["internal"] }],
      authenticatorSelection: { residentKey: "required", userVerification: "preferred" },
    },
  });
  assert.deepEqual(bytes(options.challenge), [222, 173, 190, 239]);
  assert.deepEqual(bytes(options.user.id), [0, 1, 2, 3]);
  assert.deepEqual(bytes(options.excludeCredentials?.[0].id), [4, 5]);
  assert.equal(options.rp.name, "Liftgate");
  assert.equal(options.authenticatorSelection?.residentKey, "required");
});

test("request options decode the server json without allowCredentials", () => {
  const options = requestOptions({ publicKey: { challenge: "AAE", rpId: "liftgate.dev", userVerification: "preferred" } });
  assert.deepEqual(bytes(options.challenge), [0, 1]);
  assert.equal(options.allowCredentials, undefined);
  assert.equal(options.rpId, "liftgate.dev");
});

test("credentials serialize to base64url json", () => {
  const buffer = (...values: number[]) => new Uint8Array(values).buffer;
  const base = { id: "AQI", rawId: buffer(1, 2), type: "public-key", getClientExtensionResults: () => ({}) };
  const assertion = credentialJson({
    ...base,
    response: { clientDataJSON: buffer(3), authenticatorData: buffer(4), signature: buffer(5), userHandle: buffer(6) },
  } as unknown as PublicKeyCredential);
  assert.deepEqual(assertion, {
    id: "AQI",
    rawId: "AQI",
    type: "public-key",
    clientExtensionResults: {},
    response: { clientDataJSON: "Aw", authenticatorData: "BA", signature: "BQ", userHandle: "Bg" },
  });
  const attestation = credentialJson({
    ...base,
    response: { clientDataJSON: buffer(3), attestationObject: buffer(7), getTransports: () => ["internal"] },
  } as unknown as PublicKeyCredential);
  assert.deepEqual(attestation.response, { clientDataJSON: "Aw", attestationObject: "Bw", transports: ["internal"] });
});
