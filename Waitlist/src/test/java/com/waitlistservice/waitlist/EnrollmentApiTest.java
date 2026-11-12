package com.waitlistservice.waitlist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class EnrollmentApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM waitlist_entry");
        jdbc.update("DELETE FROM enrollment");
        jdbc.update("DELETE FROM waitlist_sequence");
        jdbc.update("DELETE FROM offering_prerequisite");
        jdbc.update("DELETE FROM learner_completion");
        jdbc.update("DELETE FROM course_offering");
        jdbc.update("DELETE FROM learner");
    }

    @Test
    void enrollWaitlistAndDropThroughHttp() throws Exception {
        mockMvc.perform(post("/api/offerings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"api-101","capacity":1,"prerequisiteCourseIds":["intro"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capacity").value(1));

        mockMvc.perform(post("/api/learners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"ada","completedCourseIds":["intro"]}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/learners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"bea","completedCourseIds":["intro"]}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/offerings/api-101/enrollments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"learnerId":"ada"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ENROLLED"));

        mockMvc.perform(post("/api/offerings/api-101/enrollments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"learnerId":"bea"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("WAITLISTED"));

        mockMvc.perform(put("/api/learners/bea/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"completedCourseIds":[]}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/learners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"cy","completedCourseIds":["intro"]}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/offerings/api-101/enrollments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"learnerId":"cy"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("WAITLISTED"));

        mockMvc.perform(delete("/api/offerings/api-101/enrollments/ada"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DROPPED"))
                .andExpect(jsonPath("$.promotedLearnerId").value("cy"));

        mockMvc.perform(post("/api/offerings/api-101/enrollments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"learnerId":"  "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.outcome").value("INVALID_REQUEST"));
    }
}
