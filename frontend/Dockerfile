FROM node:22-alpine AS build
WORKDIR /app
RUN corepack enable && corepack prepare pnpm@10.28.0 --activate
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
# These are public build settings. Backend secrets never enter this context.
# The domain subpath build passes NUXT_APP_BASE_URL=/office/ and matching API prefixes.
ARG NUXT_APP_BASE_URL=/
ARG VITE_API_BASE_URL=
ARG NUXT_PUBLIC_API_BASE_URL=
ENV NUXT_APP_BASE_URL=$NUXT_APP_BASE_URL
ENV VITE_AUTH_ENABLED=1
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV NUXT_PUBLIC_API_BASE_URL=$NUXT_PUBLIC_API_BASE_URL
RUN pnpm generate

FROM nginx:stable-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/.output/public /usr/share/nginx/html
EXPOSE 80
