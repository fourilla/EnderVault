import type { ActionResponse } from './types';
import { postJson } from './settings-api';

type CredentialDescriptorJson = { id: string; type?: PublicKeyCredentialType; transports?: AuthenticatorTransport[] };
type CreationPublicKeyJson = {
  challenge: string;
  user: { id: string } & Record<string, unknown>;
  excludeCredentials?: CredentialDescriptorJson[];
} & Record<string, unknown>;
type CreationOptionsJson = { publicKey?: CreationPublicKeyJson } & Record<string, unknown>;

const base64UrlToArrayBuffer = (value: string) => {
  const padding = '='.repeat((4 - value.length % 4) % 4);
  const base64 = (value + padding).replace(/-/g, '+').replace(/_/g, '/');
  const binary = window.atob(base64);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0)).buffer;
};

const arrayBufferToBase64Url = (buffer: ArrayBuffer | null) => {
  if (buffer === null) return null;
  const binary = Array.from(new Uint8Array(buffer), (byte) => String.fromCharCode(byte)).join('');
  return window.btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
};

const prepareCreationOptions = (options: CreationOptionsJson): CredentialCreationOptions => {
  const source = (options.publicKey ?? options) as CreationPublicKeyJson;
  const publicKey = {
    ...source,
    challenge: base64UrlToArrayBuffer(source.challenge),
    user: { ...source.user, id: base64UrlToArrayBuffer(source.user.id) },
    excludeCredentials: source.excludeCredentials?.map((credential) => ({
      ...credential,
      id: base64UrlToArrayBuffer(credential.id),
    })),
  } as unknown as PublicKeyCredentialCreationOptions;
  return { publicKey };
};

const credentialToJson = (credential: PublicKeyCredential) => {
  const response = credential.response as AuthenticatorAttestationResponse;
  return {
    id: credential.id,
    rawId: arrayBufferToBase64Url(credential.rawId),
    type: credential.type,
    authenticatorAttachment: credential.authenticatorAttachment || null,
    clientExtensionResults: credential.getClientExtensionResults(),
    response: {
      clientDataJSON: arrayBufferToBase64Url(response.clientDataJSON),
      attestationObject: arrayBufferToBase64Url(response.attestationObject),
      transports: typeof response.getTransports === 'function' ? response.getTransports() : [],
    },
  };
};

export const registerPasskey = async (label: string): Promise<ActionResponse> => {
  if (!window.PublicKeyCredential) throw new Error('This browser does not support passkeys.');
  if (!window.isSecureContext) throw new Error('Passkeys require HTTPS or localhost.');
  const options = await postJson<CreationOptionsJson>('/api/v1/settings/passkeys/register/options');
  const credential = await navigator.credentials.create(prepareCreationOptions(options));
  if (!(credential instanceof PublicKeyCredential)) throw new Error('Passkey registration was cancelled.');
  return postJson<ActionResponse>('/api/v1/settings/passkeys/register/finish', {
    label,
    credential: credentialToJson(credential),
  });
};
