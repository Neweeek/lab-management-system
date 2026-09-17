FROM node:22-alpine AS build
WORKDIR /frontend
# 必须同时拷贝 lockfile 并用 npm ci：否则 install 会忽略已提交的 package-lock.json，
# 而 package.json 里的依赖是 "latest"，两次构建可能装到不同大版本。
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM caddy:2.8-alpine
COPY deploy/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /frontend/dist /srv
