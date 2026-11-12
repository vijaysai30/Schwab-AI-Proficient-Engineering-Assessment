package com.waitlistservice.waitlist.domain;

import java.util.Set;

public record LearnerView(String id, Set<String> completedCourseIds) {
}
