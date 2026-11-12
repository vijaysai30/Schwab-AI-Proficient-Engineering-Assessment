package com.waitlistservice.waitlist.domain;

import java.util.List;

public record OfferingView(
        String id,
        int capacity,
        List<String> prerequisiteCourseIds,
        List<String> enrolledLearnerIds,
        List<String> waitlistedLearnerIds
) {
}
