FROM node:22-alpine
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY server.js ./
ENV PORT=8080
EXPOSE 8080
USER node
# Channels are kept in /tmp: the image's app dir is read-only for the node user, and losing the
# file on redeploy is fine because phones re-register their channels when they reconnect.
ENV DATA_FILE=/tmp/channels.json
CMD ["node", "server.js"]
