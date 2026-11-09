# Keycloak with the research id provider, configuration pre-built for Postgres.
# Build the jar first: mvn verify
#
# Keep the base image's version equal to keycloak.version in pom.xml.

FROM quay.io/keycloak/keycloak:26.8.1 AS builder
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true
COPY --chown=keycloak:keycloak target/keycloak-research-id-provider-*.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.1
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized"]
