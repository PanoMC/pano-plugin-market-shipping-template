import { panoPlugin } from '@panomc/plugin-kit/rollup';

// The build is the kit preset (@panomc/plugin-kit): server and client bundles, the entry wrapper (panoSdk = 2, the views
// registered from their `export const view`), the svelte version guard, PANO_SDK_DIR, DEV and BUNDLE_SDK.
export default panoPlugin();
