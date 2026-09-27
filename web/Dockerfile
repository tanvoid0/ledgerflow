# Built from the repo root (docker build -f web/Dockerfile .), not web/: the dev portal's perf
# tab globs docs/perf/*.json at build time, and that directory sits above web/.
FROM node:24-alpine AS build
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
COPY docs/perf/ ../docs/perf/
RUN npm run build

FROM nginx:alpine
ENV GATEWAY_URL=http://gateway-service:8080
COPY web/nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /src/web/dist /usr/share/nginx/html
EXPOSE 80
