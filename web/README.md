# web

`npm install && npm run dev` — http://localhost:5173, proxies `/api` to `http://localhost:8088` (the gateway). `npm run build` for the nginx image; `npm run lint` and `tsc -b` are the checks.

Env vars (both optional, default to the local Keycloak realm): `VITE_OIDC_AUTHORITY`, `VITE_OIDC_CLIENT_ID`.

In containers, `GATEWAY_URL` (default `http://gateway-service:8080`) sets nginx's upstream via `envsubst`.
