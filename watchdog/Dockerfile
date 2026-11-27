FROM alpine:3.22
RUN apk add --no-cache android-tools tzdata
COPY watchdog.sh /usr/local/bin/watchdog
RUN chmod 755 /usr/local/bin/watchdog
CMD ["watchdog"]
