FROM node:22-alpine AS build
WORKDIR /frontend
COPY package.json ./
RUN npm install
COPY . .
RUN npm run build

FROM caddy:2.8-alpine
COPY deploy/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /frontend/dist /srv
