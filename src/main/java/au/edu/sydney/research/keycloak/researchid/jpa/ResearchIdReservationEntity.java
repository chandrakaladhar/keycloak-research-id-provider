package au.edu.sydney.research.keycloak.researchid.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;

/**
 * One reserved research id. Rows are never deleted or updated, so an id is never reused, even after
 * the user is deleted. Created by {@code META-INF/research-id-changelog.xml}.
 *
 * <p>No foreign key to the user table: the reservation is committed in its own transaction, before
 * the user created by a first broker login is committed.
 */
@Entity
@Table(name = "RESEARCH_ID_RESERVATION")
@NamedQuery(
        name = ResearchIdReservationEntity.FIND_BY_USER,
        query = "select r from ResearchIdReservationEntity r"
                + " where r.realmId = :realmId and r.userId = :userId")
public class ResearchIdReservationEntity {

    static final String FIND_BY_USER = "ResearchIdReservation.findByUser";

    /** Primary key: an id can be reserved only once, across all realms and replicas. */
    @Id
    @Column(name = "RESEARCH_ID", length = 12, nullable = false, updatable = false)
    private String researchId;

    /** With {@link #userId}, unique: one reservation per user. */
    @Column(name = "REALM_ID", nullable = false, updatable = false)
    private String realmId;

    @Column(name = "USER_ID", nullable = false, updatable = false)
    private String userId;

    /** Milliseconds since the epoch, as Keycloak stores its own timestamps. */
    @Column(name = "CREATED_TIMESTAMP", nullable = false, updatable = false)
    private long createdTimestamp;

    protected ResearchIdReservationEntity() {
        // For JPA.
    }

    ResearchIdReservationEntity(
            String researchId, String realmId, String userId, long createdTimestamp) {
        this.researchId = researchId;
        this.realmId = realmId;
        this.userId = userId;
        this.createdTimestamp = createdTimestamp;
    }

    public String getResearchId() {
        return researchId;
    }

    public String getRealmId() {
        return realmId;
    }

    public String getUserId() {
        return userId;
    }

    public long getCreatedTimestamp() {
        return createdTimestamp;
    }
}
