FROM node:22-bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates \
    && npm install -g @openai/codex@0.165.0 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY package.json ./
COPY src ./src

USER node
CMD ["node", "src/index.mjs"]

