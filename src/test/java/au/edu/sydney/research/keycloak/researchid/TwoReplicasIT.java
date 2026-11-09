package au.edu.sydney.research.keycloak.researchid;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPAttributePermissions;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Two Keycloak replicas on one Postgres, as in production, with sign-ins spread across both at
 * once. Needs Docker.
 */
class TwoReplicasIT {

    private static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.8.1";
    private static final String POSTGRES_IMAGE = "postgres:17.11-alpine";
    private static final String REALM = "research-id-it";
    private static final String PASSWORD = "it-password-1";

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Network network;
    private static PostgreSQLContainer postgres;
    private static final List<KeycloakContainer> replicas = new ArrayList<>();
    /** Both replicas' logs, to check the provider's own messages. */
    private static final StringBuffer keycloakLogs = new StringBuffer();
    private static RealmResource realm;
    private static String realmId;

    @BeforeAll
    static void startReplicas() {
        network = Network.newNetwork();
        postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("db")
                .withDatabaseName("keycloak");
        postgres.start();

        // One after the other, so only the first creates the schema and imports the realm.
        replicas.add(replica().withRealmImportFile("/research-id-it-realm.json"));
        replicas.get(0).start();
        replicas.add(replica());
        replicas.get(1).start();

        realm = replicas.get(0).getKeycloakAdminClient().realm(REALM);
        realmId = realm.toRepresentation().getId();
        declareResearchIdInUserProfile();
    }

    private static KeycloakContainer replica() {
        return new KeycloakContainer(KEYCLOAK_IMAGE)
                .withNetwork(network)
                .withProviderClassesFrom("target/classes")
                .withEnv("KC_DB", "postgres")
                .withEnv("KC_DB_URL", "jdbc:postgresql://db:5432/keycloak")
                .withEnv("KC_DB_USERNAME", postgres.getUsername())
                .withEnv("KC_DB_PASSWORD", postgres.getPassword())
                .withLogConsumer(frame -> keycloakLogs.append(frame.getUtf8String()))
                .withStartupTimeout(Duration.ofMinutes(5));
    }

    /** As production should: research_id viewable by the person, editable only by admins. */
    private static void declareResearchIdInUserProfile() {
        UPConfig config = realm.users().userProfile().getConfiguration();
        UPAttribute attribute = new UPAttribute(EnsureResearchIdAuthenticator.ATTRIBUTE);
        attribute.setDisplayName("Research id");
        attribute.setPermissions(new UPAttributePermissions(Set.of("admin", "user"), Set.of("admin")));
        config.addOrReplaceAttribute(attribute);
        realm.users().userProfile().update(config);
    }

    @AfterAll
    static void stopReplicas() {
        replicas.forEach(KeycloakContainer::stop);
        if (postgres != null) {
            postgres.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    @Test
    void manyNewPeopleSigningInAtOnceGetUniqueValidIds() throws Exception {
        List<String> usernames = createUsers("many", 40);

        List<Optional<String>> ids = concurrently(usernames.size(), i -> signIn(i % 2, usernames.get(i)));

        Set<String> distinct = new HashSet<>();
        for (Optional<String> id : ids) {
            assertThat(id).isPresent();
            assertThat(ResearchId.isCanonical(id.get())).as(id.get()).isTrue();
            distinct.add(id.get());
        }
        assertThat(distinct).hasSize(usernames.size());
        assertThat(reservationsFor(usernames)).isEqualTo(usernames.size());
        assertThat(keycloakLogs).contains("Research id " + ids.get(0).get() + " set for user");
    }

    @Test
    void onePersonSigningInOnBothReplicasAtOnceGetsOneId() throws Exception {
        String username = createUsers("same", 1).get(0);

        List<Optional<String>> ids = concurrently(20, i -> signIn(i % 2, username));

        Set<String> distinct = ids.stream().map(Optional::orElseThrow).collect(Collectors.toSet());
        assertThat(distinct).hasSize(1);
        assertThat(reservationsFor(List.of(username))).isEqualTo(1);
        // Simultaneous writes may leave several copies of the same value in Keycloak's attribute
        // table, which has no unique constraint; never different values.
        assertThat(attributeValuesOf(username)).containsOnly(distinct.iterator().next());

        // The next sign-in collapses any copies to one value.
        assertThat(signIn(0, username)).hasValue(distinct.iterator().next());
        assertThat(attributeValuesOf(username)).containsExactly(distinct.iterator().next());
    }

    @Test
    void theIdNeverChangesOnLaterSignIns() throws Exception {
        String username = createUsers("later", 1).get(0);

        String first = signIn(0, username).orElseThrow();

        assertThat(signIn(1, username)).hasValue(first);
        assertThat(signIn(0, username)).hasValue(first);
    }

    @Test
    void aRemovedAttributeIsRestoredFromTheReservation() throws Exception {
        String username = createUsers("removed", 1).get(0);
        String first = signIn(0, username).orElseThrow();

        UserResource user = userResource(username);
        UserRepresentation rep = user.toRepresentation();
        rep.getAttributes().remove(EnsureResearchIdAuthenticator.ATTRIBUTE);
        user.update(rep);

        assertThat(signIn(1, username)).hasValue(first);
    }

    @Test
    void aFreeIdSetByAnAdminIsKeptAndReserved() throws Exception {
        String imported = ResearchId.generate(new SplittableRandom()).value();
        String username = createUser("imported-" + shortId(), Map.of("research_id", List.of(imported)));

        assertThat(signIn(0, username)).hasValue(imported);
        assertThat(reservationsFor(List.of(username))).isEqualTo(1);
    }

    @Test
    void copiesOfTheSameIdAreCollapsedToOne() throws Exception {
        String username = createUsers("copies", 1).get(0);
        String id = signIn(0, username).orElseThrow();

        // What simultaneous first sign-ins can leave behind, set up on purpose here. The admin
        // API refuses several values for a single-valued attribute, so straight into the table.
        addAttributeRows(username, id, id);
        assertThat(attributeValuesOf(username)).containsExactly(id, id, id);

        assertThat(signIn(0, username)).hasValue(id);
        assertThat(attributeValuesOf(username)).containsExactly(id);
    }

    @Test
    void differentIdsOnOneAccountAreRefused() throws Exception {
        String username = createUsers("different", 1).get(0);
        String id = signIn(0, username).orElseThrow();
        String other = ResearchId.generate(new SplittableRandom()).value();

        addAttributeRows(username, other);

        assertThat(tokenRequest(0, username).statusCode()).isNotEqualTo(200);
    }

    @Test
    void anIdBelongingToSomeoneElseIsRefused() throws Exception {
        List<String> two = createUsers("clash", 2);
        String taken = signIn(0, two.get(0)).orElseThrow();

        UserResource second = userResource(two.get(1));
        UserRepresentation rep = second.toRepresentation();
        rep.setAttributes(Map.of(EnsureResearchIdAuthenticator.ATTRIBUTE, List.of(taken)));
        second.update(rep);

        HttpResponse<String> response = tokenRequest(1, two.get(1));
        assertThat(response.statusCode()).isNotEqualTo(200);
        assertThat(response.body()).doesNotContain("access_token");
        assertThat(keycloakLogs).contains(
                "User " + second.toRepresentation().getId() + " in realm " + REALM
                        + " has a research id that is malformed, or reserved for another account");
    }

    // --- helpers ---------------------------------------------------------------------------

    private interface Attempt {
        Optional<String> run(int index) throws Exception;
    }

    /** Runs {@code count} attempts, released together so they overlap as much as possible. */
    private static List<Optional<String>> concurrently(int count, Attempt attempt) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(count)) {
            List<Future<Optional<String>>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                Callable<Optional<String>> task = () -> {
                    start.await();
                    return attempt.run(index);
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            List<Optional<String>> results = new ArrayList<>();
            for (Future<Optional<String>> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    /** Signs in through the given replica; returns the research_id claim of the access token. */
    private static Optional<String> signIn(int replica, String username) throws Exception {
        HttpResponse<String> response = tokenRequest(replica, username);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String token = JSON.readTree(response.body()).get("access_token").asText();
        JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        return Optional.ofNullable(claims.get("research_id")).map(JsonNode::asText);
    }

    private static HttpResponse<String> tokenRequest(int replica, String username) throws Exception {
        String form = "grant_type=password&client_id=it-client&scope=openid"
                + "&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(replicas.get(replica).getAuthServerUrl()
                        + "/realms/" + REALM + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> createUsers(String prefix, int count) {
        String run = shortId();
        List<String> usernames = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            usernames.add(createUser(prefix + "-" + run + "-" + i, Map.of()));
        }
        return usernames;
    }

    private static String createUser(String username, Map<String, List<String>> attributes) {
        CredentialRepresentation password = new CredentialRepresentation();
        password.setType(CredentialRepresentation.PASSWORD);
        password.setValue(PASSWORD);
        password.setTemporary(false);

        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(username + "@example.org");
        user.setEmailVerified(true);
        user.setFirstName("Test");
        user.setLastName("Person");
        user.setEnabled(true);
        user.setAttributes(attributes);
        user.setCredentials(List.of(password));

        try (var response = realm.users().create(user)) {
            assertThat(response.getStatus()).isEqualTo(201);
        }
        return username;
    }

    private static UserResource userResource(String username) {
        String id = realm.users().searchByUsername(username, true).get(0).getId();
        return realm.users().get(id);
    }

    private static List<String> attributeValuesOf(String username) {
        return userResource(username).toRepresentation()
                .getAttributes().get(EnsureResearchIdAuthenticator.ATTRIBUTE);
    }

    /**
     * Adds research_id rows to Keycloak's own attribute table, as simultaneous requests can, then
     * clears both replicas' user caches so they read them.
     */
    private static void addAttributeRows(String username, String... values) throws Exception {
        String userId = userResource(username).toRepresentation().getId();
        try (Connection db = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                PreparedStatement insert = db.prepareStatement(
                        "insert into user_attribute (id, name, value, user_id)"
                                + " values (gen_random_uuid()::text, ?, ?, ?)")) {
            for (String value : values) {
                insert.setString(1, EnsureResearchIdAuthenticator.ATTRIBUTE);
                insert.setString(2, value);
                insert.setString(3, userId);
                insert.executeUpdate();
            }
        }
        for (KeycloakContainer replica : replicas) {
            replica.getKeycloakAdminClient().realm(REALM).clearUserCache();
        }
    }

    /** Rows in the reservation table for these users. */
    private static int reservationsFor(List<String> usernames) throws Exception {
        List<String> userIds = usernames.stream()
                .map(name -> realm.users().searchByUsername(name, true).get(0).getId())
                .toList();
        try (Connection db = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                PreparedStatement query = db.prepareStatement(
                        "select count(*) from research_id_reservation"
                                + " where realm_id = ? and user_id = any (?)")) {
            query.setString(1, realmId);
            query.setArray(2, db.createArrayOf("varchar", userIds.toArray()));
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
