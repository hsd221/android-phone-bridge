FROM node:22-bookworm-slim AS build
WORKDIR /app
COPY package.json package-lock.json tsconfig.json ./
RUN npm ci
COPY bridge/src ./bridge/src
RUN npm run build && npm prune --omit=dev

FROM node:22-bookworm-slim
ENV NODE_ENV=production PHONE_BRIDGE_CONFIG=/data/bridge.config.json
WORKDIR /app
COPY --from=build /app/package.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY docker/entrypoint.sh ./entrypoint.sh
RUN mkdir /data && chown node:node /data && chmod 700 /data
USER node
EXPOSE 8787
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 CMD ["node", "/app/dist/healthcheck.js"]
ENTRYPOINT ["sh", "/app/entrypoint.sh"]
CMD ["serve"]
