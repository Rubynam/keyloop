# Locust load generator for Spec06. Pinned (no latest): one image = one set of numbers.
FROM locustio/locust:2.46.7

# zoneinfo needs the IANA database; the slim base may not ship /usr/share/zoneinfo
RUN pip install --no-cache-dir tzdata==2025.2

WORKDIR /loadtest
COPY --chown=locust:locust . .
RUN mkdir -p reports && chown locust:locust reports

# Defaults in locust.conf; profile and target come from the environment (compose)
ENTRYPOINT ["locust", "--config", "/loadtest/locust.conf"]
