/// <reference types="vite/client" />
interface ImportMetaEnv {
  readonly VITE_API_URL?: string;
  readonly VITE_PLATFORM_URL?: string;
  readonly VITE_USE_MOCKS?: string;
  readonly VITE_BACKEND_LIVE?: string;
  readonly VITE_MOCK_FALLBACK?: string;
}
interface ImportMeta {
  readonly env: ImportMetaEnv;
}
declare module "*.js";
