package au.edu.sydney.research.keycloak.researchid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ResearchIdMinterTest {

    private static final String REALM = "realm-1";

    /** In-memory store with the same two rules as the database: id unique, one per user. */
    private static class FakeStore implements ReservationStore {
        final Map<ResearchId, String> ownerById = new HashMap<>();
        final Map<String, ResearchId> idByUser = new HashMap<>();
        /** Runs just before a reservation is attempted: simulates another replica acting first. */
        Runnable beforeReserve = () -> { };
        int reserveCalls;

        @Override
        public synchronized Optional<ResearchId> findByUser(String realmId, String userId) {
            return Optional.ofNullable(idByUser.get(realmId + "/" + userId));
        }

        @Override
        public synchronized boolean tryReserve(String realmId, String userId, ResearchId id) {
            reserveCalls++;
            beforeReserve.run();
            String user = realmId + "/" + userId;
            if (ownerById.containsKey(id) || idByUser.containsKey(user)) {
                return false;
            }
            ownerById.put(id, user);
            idByUser.put(user, id);
            return true;
        }

        void reserve(String userId, ResearchId id) {
            ownerById.put(id, REALM + "/" + userId);
            idByUser.put(REALM + "/" + userId, id);
        }
    }

    private static Supplier<ResearchId> sequence(ResearchId... ids) {
        Deque<ResearchId> queue = new ArrayDeque<>(List.of(ids));
        return queue::removeFirst;
    }

    private static ResearchId id(int seed) {
        return ResearchId.generate(new SplittableRandom(seed));
    }

    @Test
    void mintsANewIdForANewUser() {
        FakeStore store = new FakeStore();
        ResearchIdMinter minter = new ResearchIdMinter(store, sequence(id(1)));

        assertThat(minter.ensure(REALM, "alice")).isEqualTo(id(1));
        assertThat(store.findByUser(REALM, "alice")).hasValue(id(1));
    }

    @Test
    void returnsTheReservedIdWithoutMintingAgain() {
        FakeStore store = new FakeStore();
        store.reserve("alice", id(1));
        ResearchIdMinter minter = new ResearchIdMinter(store, sequence());

        assertThat(minter.ensure(REALM, "alice")).isEqualTo(id(1));
        assertThat(store.reserveCalls).isZero();
    }

    @Test
    void triesAnotherCandidateWhenTheIdIsTaken() {
        FakeStore store = new FakeStore();
        store.reserve("bob", id(1));
        ResearchIdMinter minter = new ResearchIdMinter(store, sequence(id(1), id(2)));

        assertThat(minter.ensure(REALM, "alice")).isEqualTo(id(2));
        assertThat(store.ownerById).containsEntry(id(1), REALM + "/bob");
    }

    @Test
    void takesTheIdAnotherReplicaJustReservedForTheSameUser() {
        FakeStore store = new FakeStore();
        // Another replica reserves id(9) for alice between our lookup and our insert.
        store.beforeReserve = () -> {
            store.beforeReserve = () -> { };
            store.reserve("alice", id(9));
        };
        ResearchIdMinter minter = new ResearchIdMinter(store, sequence(id(1)));

        assertThat(minter.ensure(REALM, "alice")).isEqualTo(id(9));
        assertThat(store.ownerById).doesNotContainKey(id(1));
    }

    @Test
    void givesUpAfterTheMaximumAttempts() {
        FakeStore store = new FakeStore();
        store.reserve("bob", id(1));
        ResearchIdMinter minter = new ResearchIdMinter(store, () -> id(1));

        assertThatThrownBy(() -> minter.ensure(REALM, "alice"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.reserveCalls).isEqualTo(ResearchIdMinter.MAX_ATTEMPTS);
    }

    @Test
    void separatesRealms() {
        FakeStore store = new FakeStore();
        ResearchIdMinter minter = new ResearchIdMinter(store, sequence(id(1), id(2)));

        assertThat(minter.ensure("realm-a", "alice")).isEqualTo(id(1));
        assertThat(minter.ensure("realm-b", "alice")).isEqualTo(id(2));
    }

    @Test
    void confirmsAnIdAlreadyReservedForTheUser() {
        FakeStore store = new FakeStore();
        store.reserve("alice", id(1));
        ResearchIdMinter minter = new ResearchIdMinter(store, sequence());

        assertThat(minter.confirm(REALM, "alice", id(1))).isTrue();
    }

    @Test
    void adoptsAFreeIdSetByAnAdmin() {
        FakeStore store = new FakeStore();
        ResearchIdMinter minter = new ResearchIdMinter(store, sequence());

        assertThat(minter.confirm(REALM, "alice", id(1))).isTrue();
        assertThat(store.findByUser(REALM, "alice")).hasValue(id(1));
    }

    @Test
    void refusesAnIdReservedForSomeoneElse() {
        FakeStore store = new FakeStore();
        store.reserve("bob", id(1));
        ResearchIdMinter minter = new ResearchIdMinter(store, sequence());

        assertThat(minter.confirm(REALM, "alice", id(1))).isFalse();
        assertThat(store.findByUser(REALM, "alice")).isEmpty();
    }

    @Test
    void refusesAnIdThatDiffersFromTheUsersReservation() {
        FakeStore store = new FakeStore();
        store.reserve("alice", id(1));
        ResearchIdMinter minter = new ResearchIdMinter(store, sequence());

        assertThat(minter.confirm(REALM, "alice", id(2))).isFalse();
    }

    @Test
    void confirmsWhenAnotherReplicaAdoptsTheSameIdAtOnce() {
        FakeStore store = new FakeStore();
        store.beforeReserve = () -> {
            store.beforeReserve = () -> { };
            store.reserve("alice", id(1));
        };
        ResearchIdMinter minter = new ResearchIdMinter(store, sequence());

        assertThat(minter.confirm(REALM, "alice", id(1))).isTrue();
    }
}
