package com.waitlistservice.waitlist.persistence;

import com.waitlistservice.waitlist.domain.LearnerView;
import com.waitlistservice.waitlist.domain.OfferingView;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class RegistrationRepository {

    private final JdbcTemplate jdbc;

    public RegistrationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record OfferingRecord(String id, int capacity) {
    }

    public void insertOffering(String offeringId, int capacity, Set<String> prerequisiteCourseIds) {
        jdbc.update("INSERT INTO course_offering(id, capacity) VALUES (?, ?)", offeringId, capacity);
        jdbc.update("INSERT INTO waitlist_sequence(offering_id, next_value) VALUES (?, 1)", offeringId);
        for (String courseId : prerequisiteCourseIds) {
            jdbc.update(
                    "INSERT INTO offering_prerequisite(offering_id, course_id) VALUES (?, ?)",
                    offeringId,
                    courseId
            );
        }
    }

    public void insertLearner(String learnerId, Set<String> completedCourseIds) {
        jdbc.update("INSERT INTO learner(id) VALUES (?)", learnerId);
        replaceCompletions(learnerId, completedCourseIds);
    }

    public void replaceCompletions(String learnerId, Set<String> completedCourseIds) {
        lockLearner(learnerId);
        jdbc.update("DELETE FROM learner_completion WHERE learner_id = ?", learnerId);
        for (String courseId : completedCourseIds) {
            jdbc.update(
                    "INSERT INTO learner_completion(learner_id, course_id) VALUES (?, ?)",
                    learnerId,
                    courseId
            );
        }
    }

    public boolean learnerExists(String learnerId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM learner WHERE id = ?",
                Integer.class,
                learnerId
        );
        return count != null && count > 0;
    }

    public boolean offeringExists(String offeringId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM course_offering WHERE id = ?",
                Integer.class,
                offeringId
        );
        return count != null && count > 0;
    }

    public Optional<OfferingRecord> lockOffering(String offeringId) {
        List<OfferingRecord> rows = jdbc.query(
                "SELECT id, capacity FROM course_offering WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> new OfferingRecord(rs.getString("id"), rs.getInt("capacity")),
                offeringId
        );
        return rows.stream().findFirst();
    }

    public Optional<OfferingView> findOffering(String offeringId) {
        List<OfferingRecord> rows = jdbc.query(
                "SELECT id, capacity FROM course_offering WHERE id = ?",
                (rs, rowNum) -> new OfferingRecord(rs.getString("id"), rs.getInt("capacity")),
                offeringId
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        OfferingRecord offering = rows.get(0);
        return Optional.of(new OfferingView(
                offering.id(),
                offering.capacity(),
                prerequisiteCourseIds(offeringId),
                enrolledLearnerIds(offeringId),
                waitlistedLearnerIds(offeringId)
        ));
    }

    public Optional<LearnerView> findLearner(String learnerId) {
        if (!learnerExists(learnerId)) {
            return Optional.empty();
        }
        return Optional.of(new LearnerView(learnerId, completedCourseIds(learnerId)));
    }

    public boolean isEnrolled(String offeringId, String learnerId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM enrollment WHERE offering_id = ? AND learner_id = ?",
                Integer.class,
                offeringId,
                learnerId
        );
        return count != null && count > 0;
    }

    public boolean isWaitlisted(String offeringId, String learnerId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM waitlist_entry WHERE offering_id = ? AND learner_id = ?",
                Integer.class,
                offeringId,
                learnerId
        );
        return count != null && count > 0;
    }

    public boolean isEligible(String learnerId, String offeringId) {
        lockLearner(learnerId);
        Integer missing = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM offering_prerequisite prerequisite
                WHERE prerequisite.offering_id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM learner_completion completion
                      WHERE completion.learner_id = ?
                        AND completion.course_id = prerequisite.course_id
                  )
                """,
                Integer.class,
                offeringId,
                learnerId
        );
        return missing != null && missing == 0;
    }

    public int countEnrolled(String offeringId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM enrollment WHERE offering_id = ?",
                Integer.class,
                offeringId
        );
        return count == null ? 0 : count;
    }

    public int countEnrolledAndWaitlisted(String offeringId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM enrollment enrolled
                JOIN waitlist_entry waitlisted
                  ON enrolled.offering_id = waitlisted.offering_id
                 AND enrolled.learner_id = waitlisted.learner_id
                WHERE enrolled.offering_id = ?
                """,
                Integer.class,
                offeringId
        );
        return count == null ? 0 : count;
    }

    public void insertEnrollment(String offeringId, String learnerId) {
        jdbc.update(
                "INSERT INTO enrollment(offering_id, learner_id) VALUES (?, ?)",
                offeringId,
                learnerId
        );
    }

    public void deleteEnrollment(String offeringId, String learnerId) {
        jdbc.update(
                "DELETE FROM enrollment WHERE offering_id = ? AND learner_id = ?",
                offeringId,
                learnerId
        );
    }

    public void appendWaitlist(String offeringId, String learnerId) {
        Long sequence = jdbc.queryForObject(
                "SELECT next_value FROM waitlist_sequence WHERE offering_id = ?",
                Long.class,
                offeringId
        );
        if (sequence == null) {
            throw new IllegalStateException("Waitlist sequence is missing for offering " + offeringId);
        }
        jdbc.update(
                "UPDATE waitlist_sequence SET next_value = ? WHERE offering_id = ?",
                sequence + 1,
                offeringId
        );
        jdbc.update(
                "INSERT INTO waitlist_entry(offering_id, learner_id, sequence_no) VALUES (?, ?, ?)",
                offeringId,
                learnerId,
                sequence
        );
    }

    public void deleteWaitlistEntry(String offeringId, String learnerId) {
        jdbc.update(
                "DELETE FROM waitlist_entry WHERE offering_id = ? AND learner_id = ?",
                offeringId,
                learnerId
        );
    }

    public List<String> waitlistedLearnerIds(String offeringId) {
        return jdbc.query(
                "SELECT learner_id FROM waitlist_entry WHERE offering_id = ? ORDER BY sequence_no ASC",
                (rs, rowNum) -> rs.getString("learner_id"),
                offeringId
        );
    }

    public List<String> enrolledLearnerIds(String offeringId) {
        return jdbc.query(
                "SELECT learner_id FROM enrollment WHERE offering_id = ? ORDER BY position_seq ASC",
                (rs, rowNum) -> rs.getString("learner_id"),
                offeringId
        );
    }

    public int offeringCapacity(String offeringId) {
        Integer capacity = jdbc.queryForObject(
                "SELECT capacity FROM course_offering WHERE id = ?",
                Integer.class,
                offeringId
        );
        if (capacity == null) {
            throw new IllegalStateException("Offering capacity is missing for " + offeringId);
        }
        return capacity;
    }

    private List<String> prerequisiteCourseIds(String offeringId) {
        return jdbc.query(
                "SELECT course_id FROM offering_prerequisite WHERE offering_id = ? ORDER BY course_id ASC",
                (rs, rowNum) -> rs.getString("course_id"),
                offeringId
        );
    }

    private Set<String> completedCourseIds(String learnerId) {
        List<String> courseIds = jdbc.query(
                "SELECT course_id FROM learner_completion WHERE learner_id = ? ORDER BY course_id ASC",
                (rs, rowNum) -> rs.getString("course_id"),
                learnerId
        );
        return new LinkedHashSet<>(courseIds);
    }

    private void lockLearner(String learnerId) {
        try {
            jdbc.queryForObject(
                    "SELECT id FROM learner WHERE id = ? FOR UPDATE",
                    String.class,
                    learnerId
            );
        } catch (EmptyResultDataAccessException ex) {
            throw new IllegalStateException("Learner does not exist: " + learnerId, ex);
        }
    }
}
