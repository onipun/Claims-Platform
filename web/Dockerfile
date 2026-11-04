# Stage 1: build the Angular app (npm ci from the committed lockfile, production build).
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY angular.json tsconfig*.json tailwind.config.js proxy.conf.json ./
COPY src ./src
RUN npm run build

# Stage 2: serve the static bundle. nginx/default.conf also proxies /api to the backend services.
FROM nginx:1.29-alpine
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/web/browser /usr/share/nginx/html
